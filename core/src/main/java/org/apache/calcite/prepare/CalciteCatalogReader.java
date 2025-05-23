/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.prepare;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.function.Hints;
import org.apache.calcite.model.ModelHandler;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.AggregateFunction;
import org.apache.calcite.schema.ScalarFunction;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.TableFunction;
import org.apache.calcite.schema.TableMacro;
import org.apache.calcite.schema.Wrapper;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.schema.lookup.LikePattern;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandMetadata;
import org.apache.calcite.sql.type.SqlOperandTypeInference;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.sql.validate.SqlMoniker;
import org.apache.calcite.sql.validate.SqlMonikerImpl;
import org.apache.calcite.sql.validate.SqlMonikerType;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlNameMatchers;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedTableFunction;
import org.apache.calcite.sql.validate.SqlUserDefinedTableMacro;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.Optionality;
import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.google.common.collect.ImmutableList.toImmutableList;

import static java.util.Objects.requireNonNull;

/**
 * Implementation of {@link org.apache.calcite.prepare.Prepare.CatalogReader}
 * and also {@link org.apache.calcite.sql.SqlOperatorTable} based on tables and
 * functions defined schemas.
 */
public class CalciteCatalogReader implements Prepare.CatalogReader {
  protected final CalciteSchema rootSchema;
  protected final RelDataTypeFactory typeFactory;
  private final List<List<String>> schemaPaths;
  protected final SqlNameMatcher nameMatcher;
  protected final CalciteConnectionConfig config;

  public CalciteCatalogReader(CalciteSchema rootSchema,
      List<String> defaultSchema, RelDataTypeFactory typeFactory,
      CalciteConnectionConfig config) {
    this(rootSchema, SqlNameMatchers.withCaseSensitive(config.caseSensitive()),
        ImmutableList.of(ImmutableList.copyOf(defaultSchema),
            ImmutableList.of()),
        typeFactory, config);
  }

  protected CalciteCatalogReader(CalciteSchema rootSchema,
      SqlNameMatcher nameMatcher, List<List<String>> schemaPaths,
      RelDataTypeFactory typeFactory, CalciteConnectionConfig config) {
    this.rootSchema = requireNonNull(rootSchema, "rootSchema");
    this.nameMatcher = nameMatcher;
    this.schemaPaths =
        Util.immutableCopy(Util.isDistinct(schemaPaths)
            ? schemaPaths
            : new LinkedHashSet<>(schemaPaths));
    this.typeFactory = typeFactory;
    this.config = config;
  }

  @Override public CalciteCatalogReader withSchemaPath(List<String> schemaPath) {
    return new CalciteCatalogReader(rootSchema, nameMatcher,
        ImmutableList.of(schemaPath, ImmutableList.of()), typeFactory, config);
  }

  @Override public Prepare.@Nullable PreparingTable getTable(final List<String> names) {
    // First look in the default schema, if any.
    // If not found, look in the root schema.
    CalciteSchema.TableEntry entry = SqlValidatorUtil.getTableEntry(this, names);
    if (entry != null) {
      final Table table = entry.getTable();
      if (table instanceof Wrapper) {
        final Prepare.PreparingTable relOptTable =
            ((Wrapper) table).unwrap(Prepare.PreparingTable.class);
        if (relOptTable != null) {
          return relOptTable;
        }
      }
      return RelOptTableImpl.create(this,
          table.getRowType(typeFactory), entry, null);
    }
    return null;
  }

  @Override public CalciteConnectionConfig getConfig() {
    return config;
  }

  private Collection<org.apache.calcite.schema.Function> getFunctionsFrom(
      List<String> names) {
    final List<org.apache.calcite.schema.Function> functions2 =
        new ArrayList<>();
    final List<List<String>> schemaNameList = new ArrayList<>();
    if (names.size() > 1) {
      // Name qualified: ignore path. But we do look in "/catalog" and "/",
      // the last 2 items in the path.
      if (schemaPaths.size() > 1) {
        schemaNameList.addAll(Util.skip(schemaPaths));
      } else {
        schemaNameList.addAll(schemaPaths);
      }
    } else {
      for (List<String> schemaPath : schemaPaths) {
        CalciteSchema schema =
            SqlValidatorUtil.getSchema(rootSchema, schemaPath, nameMatcher);
        if (schema != null) {
          schemaNameList.addAll(schema.getPath());
        }
      }
    }
    for (List<String> schemaNames : schemaNameList) {
      CalciteSchema schema =
          SqlValidatorUtil.getSchema(rootSchema,
              Iterables.concat(schemaNames, Util.skipLast(names)), nameMatcher);
      if (schema != null) {
        final String name = Util.last(names);
        boolean caseSensitive = nameMatcher.isCaseSensitive();
        functions2.addAll(schema.getFunctions(name, caseSensitive));
      }
    }
    return functions2;
  }

  @Override public @Nullable RelDataType getNamedType(SqlIdentifier typeName) {
    CalciteSchema.TypeEntry typeEntry = SqlValidatorUtil.getTypeEntry(getRootSchema(), typeName);
    if (typeEntry != null) {
      return typeEntry.getType().apply(typeFactory);
    } else {
      return null;
    }
  }

  @Override public List<SqlMoniker> getAllSchemaObjectNames(List<String> names) {
    final CalciteSchema schema =
        SqlValidatorUtil.getSchema(rootSchema, names, nameMatcher);
    if (schema == null) {
      return ImmutableList.of();
    }
    final ImmutableList.Builder<SqlMoniker> result = new ImmutableList.Builder<>();

    // Add root schema if not anonymous
    if (!schema.name.equals("")) {
      result.add(moniker(schema, null, SqlMonikerType.SCHEMA));
    }

    final Map<String, CalciteSchema> schemaMap = schema.getSubSchemaMap();

    for (String subSchema : schemaMap.keySet()) {
      result.add(moniker(schema, subSchema, SqlMonikerType.SCHEMA));
    }

    for (String table : schema.getTableNames(LikePattern.any())) {
      result.add(moniker(schema, table, SqlMonikerType.TABLE));
    }

    final NavigableSet<String> functions = schema.getFunctionNames();
    for (String function : functions) { // views are here as well
      result.add(moniker(schema, function, SqlMonikerType.FUNCTION));
    }
    return result.build();
  }

  private static SqlMonikerImpl moniker(CalciteSchema schema, @Nullable String name,
      SqlMonikerType type) {
    final List<String> path = schema.path(name);
    if (path.size() == 1
        && !schema.root().name.equals("")
        && type == SqlMonikerType.SCHEMA) {
      type = SqlMonikerType.CATALOG;
    }
    return new SqlMonikerImpl(path, type);
  }

  @Override public List<List<String>> getSchemaPaths() {
    return schemaPaths;
  }

  @Override public Prepare.@Nullable PreparingTable getTableForMember(List<String> names) {
    return getTable(names);
  }

  @SuppressWarnings("deprecation")
  @Override public @Nullable RelDataTypeField field(RelDataType rowType, String alias) {
    return nameMatcher.field(rowType, alias);
  }

  @SuppressWarnings("deprecation")
  @Override public boolean matches(String string, String name) {
    return nameMatcher.matches(string, name);
  }

  @Override public RelDataType createTypeFromProjection(final RelDataType type,
      final List<String> columnNameList) {
    return SqlValidatorUtil.createTypeFromProjection(type, columnNameList,
        typeFactory, nameMatcher.isCaseSensitive());
  }

  @Override public void lookupOperatorOverloads(final SqlIdentifier opName,
      @Nullable SqlFunctionCategory category,
      SqlSyntax syntax,
      List<SqlOperator> operatorList,
      SqlNameMatcher nameMatcher) {
    if (syntax != SqlSyntax.FUNCTION) {
      return;
    }

    final Predicate<org.apache.calcite.schema.Function> predicate;
    if (category == null) {
      predicate = function -> true;
    } else if (category.isTableFunction()) {
      predicate = function ->
          function instanceof TableMacro
              || function instanceof TableFunction;
    } else {
      predicate = function ->
          !(function instanceof TableMacro
              || function instanceof TableFunction);
    }
    getFunctionsFrom(opName.names)
        .stream()
        .filter(predicate)
        .map(function -> toOp(opName, function, config))
        .forEachOrdered(operatorList::add);
  }

  /** Creates an operator table that contains functions in the given class
   * or classes.
   *
   * @see ModelHandler#addFunctions */
  public static SqlOperatorTable operatorTable(String... classNames) {
    // Dummy schema to collect the functions
    final CalciteSchema schema =
        CalciteSchema.createRootSchema(false, false);
    for (String className : classNames) {
      ModelHandler.addFunctions(schema.plus(), null, ImmutableList.of(),
          className, "*", true);
    }

    final List<SqlOperator> list = new ArrayList<>();
    for (String name : schema.getFunctionNames()) {
      schema.getFunctions(name, true).forEach(function -> {
        final SqlIdentifier id = new SqlIdentifier(name, SqlParserPos.ZERO);
        list.add(toOp(id, function, CalciteConnectionConfig.DEFAULT));
      });
    }
    return SqlOperatorTables.of(list);
  }

  /** Converts a function to a {@link org.apache.calcite.sql.SqlOperator}. */
  private static SqlOperator toOp(SqlIdentifier name,
      final org.apache.calcite.schema.Function function, CalciteConnectionConfig config) {
    final Function<RelDataTypeFactory, List<RelDataType>> argTypesFactory =
        typeFactory -> function.getParameters()
            .stream()
            .map(o -> o.getType(typeFactory))
            .collect(toImmutableList());
    final Function<RelDataTypeFactory, List<SqlTypeFamily>> typeFamiliesFactory =
        typeFactory -> argTypesFactory.apply(typeFactory)
            .stream()
            .map(type ->
                Util.first(type.getSqlTypeName().getFamily(),
                    SqlTypeFamily.ANY))
            .collect(toImmutableList());
    final Function<RelDataTypeFactory, List<RelDataType>> paramTypesFactory =
        typeFactory ->
            argTypesFactory.apply(typeFactory)
                .stream()
                .map(type -> toSql(typeFactory, type))
                .collect(toImmutableList());

    // Use a short-lived type factory to populate "typeFamilies" and "argTypes".
    // SqlOperandMetadata.paramTypes will use the real type factory, during
    // validation.
    final RelDataTypeFactory dummyTypeFactory = new JavaTypeFactoryImpl();
    final List<RelDataType> argTypes = argTypesFactory.apply(dummyTypeFactory);
    final List<SqlTypeFamily> typeFamilies =
        typeFamiliesFactory.apply(dummyTypeFactory);

    final SqlOperandTypeInference operandTypeInference =
        InferTypes.explicit(argTypes);

    final SqlOperandMetadata operandMetadata =
        OperandTypes.operandMetadata(typeFamilies, paramTypesFactory,
            i -> function.getParameters().get(i).getName(),
            i -> function.getParameters().get(i).isOptional());

    final SqlKind kind = kind(function);
    if (function instanceof ScalarFunction) {
      final SqlReturnTypeInference returnTypeInference =
          infer((ScalarFunction) function);
      SqlSyntax syntax = getSqlSyntax(function, config);
      return new SqlUserDefinedFunction(name, kind, returnTypeInference,
          operandTypeInference, operandMetadata, function, syntax);
    } else if (function instanceof AggregateFunction) {
      final SqlReturnTypeInference returnTypeInference =
          infer((AggregateFunction) function);
      return new SqlUserDefinedAggFunction(name, kind,
          returnTypeInference, operandTypeInference,
          operandMetadata, (AggregateFunction) function, false, false,
          Optionality.FORBIDDEN);
    } else if (function instanceof TableMacro) {
      return new SqlUserDefinedTableMacro(name, kind, ReturnTypes.CURSOR,
          operandTypeInference, operandMetadata, (TableMacro) function);
    } else if (function instanceof TableFunction) {
      return new SqlUserDefinedTableFunction(name, kind, ReturnTypes.CURSOR,
          operandTypeInference, operandMetadata, (TableFunction) function);
    } else {
      throw new AssertionError("unknown function type " + function);
    }
  }

  private static SqlSyntax getSqlSyntax(org.apache.calcite.schema.Function function,
      CalciteConnectionConfig config) {
    if (!function.getParameters().isEmpty()) {
      return SqlSyntax.FUNCTION;
    }
    // Keep compatible with both Foo() and Foo function syntax for Calcite's default conformance
    if (SqlConformanceEnum.DEFAULT == config.conformance()) {
      return SqlSyntax.FUNCTION_ID_CONSTANT;
    }
    return config.conformance().allowNiladicParentheses()
        ? SqlSyntax.FUNCTION
        : SqlSyntax.FUNCTION_ID;
  }

  /** Deduces the {@link org.apache.calcite.sql.SqlKind} of a user-defined
   * function based on a {@link Hints} annotation, if present. */
  private static SqlKind kind(org.apache.calcite.schema.Function function) {
    if (function instanceof ScalarFunctionImpl) {
      Hints hints =
          ((ScalarFunctionImpl) function).method.getAnnotation(Hints.class);
      if (hints != null) {
        for (String hint : hints.value()) {
          if (hint.startsWith("SqlKind:")) {
            return SqlKind.valueOf(hint.substring("SqlKind:".length()));
          }
        }
      }
    }
    return SqlKind.OTHER_FUNCTION;
  }

  private static SqlReturnTypeInference infer(final ScalarFunction function) {
    return opBinding -> {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      final RelDataType type;
      if (function instanceof ScalarFunctionImpl) {
        type =
            ((ScalarFunctionImpl) function).getReturnType(typeFactory, opBinding);
      } else {
        type = function.getReturnType(typeFactory);
      }
      return toSql(typeFactory, type);
    };
  }

  private static SqlReturnTypeInference infer(
      final AggregateFunction function) {
    return opBinding -> {
      final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      final RelDataType type = function.getReturnType(typeFactory);
      return toSql(typeFactory, type);
    };
  }

  private static RelDataType toSql(RelDataTypeFactory typeFactory,
      RelDataType type) {
    if (type instanceof RelDataTypeFactoryImpl.JavaType
        && ((RelDataTypeFactoryImpl.JavaType) type).getJavaClass()
        == Object.class) {
      return typeFactory.createTypeWithNullability(
          typeFactory.createSqlType(SqlTypeName.ANY), true);
    }
    return JavaTypeFactoryImpl.toSql(typeFactory, type);
  }

  @Override public List<SqlOperator> getOperatorList() {
    final ImmutableList.Builder<SqlOperator> builder = ImmutableList.builder();
    for (List<String> schemaPath : schemaPaths) {
      CalciteSchema schema =
          SqlValidatorUtil.getSchema(rootSchema, schemaPath, nameMatcher);
      if (schema != null) {
        for (String name : schema.getFunctionNames()) {
          schema.getFunctions(name, true).forEach(f ->
              builder.add(toOp(new SqlIdentifier(name, SqlParserPos.ZERO), f, config)));
        }
      }
    }
    return builder.build();
  }

  @Override public CalciteSchema getRootSchema() {
    return rootSchema;
  }

  @Override public RelDataTypeFactory getTypeFactory() {
    return typeFactory;
  }

  @Override public void registerRules(RelOptPlanner planner) {
  }

  @SuppressWarnings("deprecation")
  @Override public boolean isCaseSensitive() {
    return nameMatcher.isCaseSensitive();
  }

  @Override public SqlNameMatcher nameMatcher() {
    return nameMatcher;
  }

  @Override public <C extends Object> @Nullable C unwrap(Class<C> aClass) {
    if (aClass.isInstance(this)) {
      return aClass.cast(this);
    }
    return null;
  }
}
