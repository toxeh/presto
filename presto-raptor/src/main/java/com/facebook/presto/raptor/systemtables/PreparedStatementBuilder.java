/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.raptor.systemtables;

import com.facebook.presto.spi.Domain;
import com.facebook.presto.spi.Range;
import com.facebook.presto.spi.TupleDomain;
import com.facebook.presto.spi.type.Type;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.facebook.presto.raptor.metadata.JdbcUtil.enableStreamingResults;
import static com.facebook.presto.raptor.util.UuidUtil.uuidToBytes;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Iterables.cycle;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Iterables.limit;
import static java.lang.String.format;
import static java.sql.ResultSet.CONCUR_READ_ONLY;
import static java.sql.ResultSet.TYPE_FORWARD_ONLY;
import static java.util.UUID.fromString;

public class PreparedStatementBuilder
{
    private PreparedStatementBuilder() {}

    public static PreparedStatement create(
            Connection connection,
            String sql,
            List<String> columnNames,
            List<Type> types,
            Set<Integer> uuidColumnIndexes,
            TupleDomain<Integer> tupleDomain)
            throws SQLException
    {
        checkArgument(!isNullOrEmpty(sql), "sql is null or empty");

        List<ValueBuffer> bindValues = new ArrayList<>(256);
        sql = sql + getWhereClause(tupleDomain, columnNames, types, uuidColumnIndexes, bindValues);

        PreparedStatement statement = connection.prepareStatement(sql, TYPE_FORWARD_ONLY, CONCUR_READ_ONLY);
        enableStreamingResults(statement);

        // bind values to statement
        int bindIndex = 1;
        for (ValueBuffer value : bindValues) {
            bindField(value, statement, bindIndex, uuidColumnIndexes.contains(value.getColumnIndex()));
            bindIndex++;
        }
        return statement;
    }

    private static String getWhereClause(
            TupleDomain<Integer> tupleDomain,
            List<String> columnNames,
            List<Type> types,
            Set<Integer> uuidColumnIndexes,
            List<ValueBuffer> bindValues)
    {
        if (tupleDomain.isNone()) {
            return "";
        }

        ImmutableList.Builder<String> conjunctsBuilder = ImmutableList.builder();
        Map<Integer, Domain> domainMap = tupleDomain.getDomains();
        for (Map.Entry<Integer, Domain> entry : domainMap.entrySet()) {
            int index = entry.getKey();
            String columnName = columnNames.get(index);
            Type type = types.get(index);
            conjunctsBuilder.add(toPredicate(index, columnName, type, entry.getValue(), uuidColumnIndexes, bindValues));
        }
        List<String> conjuncts = conjunctsBuilder.build();

        if (conjuncts.isEmpty()) {
            return "";
        }
        StringBuilder where = new StringBuilder("WHERE ");
        return Joiner.on(" AND\n").appendTo(where, conjuncts).toString();
    }

    private static String toPredicate(
            int columnIndex,
            String columnName,
            Type type,
            Domain domain,
            Set<Integer> uuidColumnIndexes,
            List<ValueBuffer> bindValues)
    {
        if (domain.getRanges().isNone() && domain.isNullAllowed()) {
            return columnName + " IS NULL";
        }

        if (domain.getRanges().isAll() && !domain.isNullAllowed()) {
            return columnName + " IS NOT NULL";
        }

        // Add disjuncts for ranges
        List<String> disjuncts = new ArrayList<>();
        List<Comparable<?>> singleValues = new ArrayList<>();
        for (Range range : domain.getRanges()) {
            checkState(!range.isAll()); // Already checked
            if (range.isSingleValue()) {
                singleValues.add(range.getLow().getValue());
            }
            else {
                List<String> rangeConjuncts = new ArrayList<>();
                if (!range.getLow().isLowerUnbounded()) {
                    Object bindValue = getBindValue(columnIndex, uuidColumnIndexes, range.getLow().getValue());
                    switch (range.getLow().getBound()) {
                        case ABOVE:
                            rangeConjuncts.add(toBindPredicate(columnName, ">"));
                            bindValues.add(ValueBuffer.create(columnIndex, type, bindValue));
                            break;
                        case EXACTLY:
                            rangeConjuncts.add(toBindPredicate(columnName, ">="));
                            bindValues.add(ValueBuffer.create(columnIndex, type, bindValue));
                            break;
                        case BELOW:
                            throw new IllegalStateException("Low Marker should never use BELOW bound: " + range);
                        default:
                            throw new AssertionError("Unhandled bound: " + range.getLow().getBound());
                    }
                }
                if (!range.getHigh().isUpperUnbounded()) {
                    Object bindValue = getBindValue(columnIndex, uuidColumnIndexes, range.getHigh().getValue());
                    switch (range.getHigh().getBound()) {
                        case ABOVE:
                            throw new IllegalStateException("High Marker should never use ABOVE bound: " + range);
                        case EXACTLY:
                            rangeConjuncts.add(toBindPredicate(columnName, "<="));
                            bindValues.add(ValueBuffer.create(columnIndex, type, bindValue));
                            break;
                        case BELOW:
                            rangeConjuncts.add(toBindPredicate(columnName, "<"));
                            bindValues.add(ValueBuffer.create(columnIndex, type, bindValue));
                            break;
                        default:
                            throw new AssertionError("Unhandled bound: " + range.getHigh().getBound());
                    }
                }
                // If rangeConjuncts is null, then the range was ALL, which should already have been checked for
                checkState(!rangeConjuncts.isEmpty());
                disjuncts.add("(" + Joiner.on(" AND ").join(rangeConjuncts) + ")");
            }
        }

        // Add back all of the possible single values either as an equality or an IN predicate
        if (singleValues.size() == 1) {
            disjuncts.add(toBindPredicate(columnName, "="));
            bindValues.add(ValueBuffer.create(columnIndex, type, getBindValue(columnIndex, uuidColumnIndexes, getOnlyElement(singleValues))));
        }
        else if (singleValues.size() > 1) {
            disjuncts.add(columnName + " IN (" + Joiner.on(",").join(limit(cycle("?"), singleValues.size())) + ")");
            for (Comparable<?> singleValue : singleValues) {
                bindValues.add(ValueBuffer.create(columnIndex, type, getBindValue(columnIndex, uuidColumnIndexes, singleValue)));
            }
        }

        // Add nullability disjuncts
        checkState(!disjuncts.isEmpty());
        if (domain.isNullAllowed()) {
            disjuncts.add(columnName + " IS NULL");
        }

        return "(" + Joiner.on(" OR ").join(disjuncts) + ")";
    }

    private static Object getBindValue(int columnIndex, Set<Integer> uuidColumnIndexes, Object value)
    {
        if (uuidColumnIndexes.contains(columnIndex)) {
            return uuidToBytes(fromString(((Slice) value).toStringUtf8()));
        }
        return value;
    }

    private static String toBindPredicate(String columnName, String operator)
    {
        return format("%s %s ?", columnName, operator);
    }

    private static void bindField(ValueBuffer valueBuffer, PreparedStatement preparedStatement, int parameterIndex, boolean isUuid)
            throws SQLException
    {
        Type type = valueBuffer.getType();
        if (valueBuffer.isNull()) {
            preparedStatement.setNull(parameterIndex, typeToSqlType(type));
        }
        else if (type.getJavaType() == long.class) {
            preparedStatement.setLong(parameterIndex, valueBuffer.getLong());
        }
        else if (type.getJavaType() == double.class) {
            preparedStatement.setDouble(parameterIndex, valueBuffer.getDouble());
        }
        else if (type.getJavaType() == boolean.class) {
            preparedStatement.setBoolean(parameterIndex, valueBuffer.getBoolean());
        }
        else if (type.getJavaType() == Slice.class && isUuid) {
            preparedStatement.setBytes(parameterIndex, valueBuffer.getSlice().getBytes());
        }
        else if (type.getJavaType() == Slice.class) {
            preparedStatement.setString(parameterIndex, new String(valueBuffer.getSlice().getBytes()));
        }
        else {
            throw new IllegalArgumentException("Unknown Java type: " + type.getJavaType());
        }
    }

    private static int typeToSqlType(Type type)
    {
        if (type == BIGINT) {
            return Types.BIGINT;
        }
        if (type == DOUBLE) {
            return Types.DOUBLE;
        }
        if (type == BOOLEAN) {
            return Types.BOOLEAN;
        }
        if (type == VARCHAR) {
            return Types.VARCHAR;
        }
        if (type == VARBINARY) {
            return Types.VARBINARY;
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }
}
