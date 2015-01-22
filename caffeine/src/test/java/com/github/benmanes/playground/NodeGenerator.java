/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
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
package com.github.benmanes.playground;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.Types;

/**
 * Experiments with JavaPoet to generate the different cache entry types. If successful, this
 * code will be moved into its own sourceSet for build time generation.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class NodeGenerator {
  enum Strength { STRONG, WEAK, SOFT }

  final TypeVariable<?> kTypeVar = Types.typeVariable("K");
  final TypeVariable<?> vTypeVar = Types.typeVariable("V");
  final Type kType = ClassName.get(getClass().getPackage().getName(), "K");
  final Type vType = ClassName.get(getClass().getPackage().getName(), "V");
  final ParameterSpec keySpec = ParameterSpec.builder(kType, "key")
      .addAnnotation(Nonnull.class).build();
  final ParameterSpec valueSpec = ParameterSpec.builder(vType, "value")
      .addAnnotation(Nonnull.class).build();
  final ParameterSpec weightSpec = ParameterSpec.builder(int.class, "weight")
      .addAnnotation(Nonnegative.class).build();

  void generate() throws IOException {
    TypeSpec.Builder nodeFactoryBuilder = newNodeFactoryBuilder();
    generatedNodes(nodeFactoryBuilder);
    makeJavaFile(nodeFactoryBuilder).emit(System.out);
  }

  void generatedNodes(TypeSpec.Builder nodeFactoryBuilder) {
    Set<String> seen = new HashSet<>();
    for (List<Object> combination : combinations()) {
      addNodeSpec(nodeFactoryBuilder, seen,
          (Strength) combination.get(0),
          (Strength) combination.get(1),
          (boolean) combination.get(2),
          (boolean) combination.get(3),
          (boolean) combination.get(4),
          (boolean) combination.get(5));
    }
  }

  void addNodeSpec(TypeSpec.Builder nodeFactoryBuilder, Set<String> seen, Strength keyStrength,
      Strength valueStrength, boolean expireAfterAccess, boolean expireAfterWrite,
      boolean maximum, boolean weighed) {
    String enumName = makeEnumName(keyStrength, valueStrength,
        expireAfterAccess, expireAfterWrite, maximum, weighed);
    String className = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, enumName);
    if (!seen.add(className)) {
      // skip duplicates
      return;
    }

    MethodSpec newNodeSpec = newNode()
        .addAnnotation(Override.class)
        .addCode("return new " + className + "<K, V>(key, value);\n")
        .build();
    TypeSpec typeSpec = TypeSpec.anonymousClassBuilder("")
        .addMethod(newNodeSpec)
        .build();
    nodeFactoryBuilder.addEnumConstant(enumName, typeSpec);

    TypeSpec.Builder nodeType = TypeSpec.classBuilder(className)
        .addSuperinterface(Types.parameterizedType(Node.class, kType, vType))
        .addModifiers(Modifier.STATIC, Modifier.FINAL)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addMethod(newGetter(keyStrength, kType, "key"))
        .addMethod(newGetter(valueStrength, vType, "value"))
        .addMethod(newSetter(valueStrength, vType, "value"));
    MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
        .addParameter(keySpec).addParameter(valueSpec);
    addConstructorAssignment(nodeType, constructor, keyStrength, kType, "key", Modifier.FINAL);
    addConstructorAssignment(nodeType, constructor,
        valueStrength, vType, "value", Modifier.VOLATILE);

    if(weighed) {
      nodeType.addField(int.class, "weight", Modifier.PRIVATE)
        .addMethod(newGetter(Strength.STRONG, int.class, "weight"))
        .addMethod(newSetter(Strength.STRONG, int.class, "weight"));
    }
    if (expireAfterAccess) {
      nodeType.addField(long.class, "accessTime", Modifier.PRIVATE, Modifier.VOLATILE)
      .addMethod(newGetter(Strength.STRONG, long.class, "accessTime"))
      .addMethod(newSetter(Strength.STRONG, long.class, "accessTime"));
    }
    if (expireAfterWrite) {
      nodeType.addField(long.class, "writeTime", Modifier.PRIVATE, Modifier.VOLATILE)
      .addMethod(newGetter(Strength.STRONG, long.class, "writeTime"))
      .addMethod(newSetter(Strength.STRONG, long.class, "writeTime"));
    }


    nodeFactoryBuilder.addType(nodeType
        .addMethod(constructor.build())
        .build());
  }

  private String makeEnumName(Strength keyStrength, Strength valueStrength,
      boolean expireAfterAccess, boolean expireAfterWrite, boolean maximum, boolean weighed) {
    StringBuilder name = new StringBuilder(keyStrength + "_KEYS_" + valueStrength + "_VALUES");
    if (expireAfterAccess) {
      name.append("_EXPIRE_ACCESS");
    }
    if (expireAfterWrite) {
      name.append("_EXPIRE_WRITE");
    }
    if (maximum) {
      name.append("_MAXIMUM");
      if (weighed) {
        name.append("_WEIGHT");
      } else {
        name.append("_SIZE");
      }
    }
    return name.toString();
  }

  private MethodSpec newGetter(Strength strength, Type varType, String varName) {
    String methodName = "get" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    MethodSpec.Builder getter = MethodSpec.methodBuilder(methodName)
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(varType);
    if ((varType == int.class) || (varType == long.class)) {
      getter.addAnnotation(Nonnegative.class);
    } else {
      getter.addAnnotation(Nullable.class);
    }
    if (strength == Strength.STRONG) {
      getter.addStatement("return $N", varName);
    } else {
      getter.addStatement("return $N.get()", varName);
    }
    return getter.build();
  }

  private MethodSpec newSetter(Strength strength, Type varType, String varName) {
    String methodName = "set" + Character.toUpperCase(varName.charAt(0)) + varName.substring(1);
    Type annotation = (varType == int.class) || (varType == long.class)
        ? Nonnegative.class
        : Nonnull.class;
    MethodSpec.Builder setter = MethodSpec.methodBuilder(methodName)
        .addParameter(ParameterSpec.builder(varType, varName).addAnnotation(annotation).build())
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC);
    if (strength == Strength.STRONG) {
      setter.addStatement("this.$N = $N", varName, varName);
    } else if (strength == Strength.WEAK) {
      setter.addStatement("this.$N = new WeakReference<>($N)", varName, varName);
    } else {
      setter.addStatement("this.$N = new SoftReference<>($N)", varName, varName);
    }
    return setter.build();
  }

  private void addConstructorAssignment(TypeSpec.Builder type, MethodSpec.Builder constructor,
      Strength strength, Type varType, String varName, Modifier modifier) {
    Modifier[] modifiers = { Modifier.PRIVATE, modifier };
    if (strength == Strength.STRONG) {
      type.addField(FieldSpec.builder(varType, varName, modifiers).build());
      constructor.addStatement("this.$N = $N", varName, varName);
    } else if (strength == Strength.WEAK) {
      type.addField(FieldSpec.builder(Types.parameterizedType(
          ClassName.get(WeakReference.class), varType), varName, modifiers).build());
      constructor.addStatement("this.$N = new WeakReference<>($N)", varName, varName);
    } else {
      type.addField(FieldSpec.builder(Types.parameterizedType(
          ClassName.get(SoftReference.class), varType), varName, modifiers).build());
      constructor.addStatement("this.$N = new SoftReference<>($N)", varName, varName);
    }
  }

  Set<List<Object>> combinations() {
    Set<Strength> keyStrengths = ImmutableSet.of(Strength.STRONG, Strength.WEAK);
    Set<Strength> valueStrengths = ImmutableSet.of(Strength.STRONG, Strength.WEAK, Strength.SOFT);
    Set<Boolean> expireAfterAccess = ImmutableSet.of(false, true);
    Set<Boolean> expireAfterWrite = ImmutableSet.of(false, true);
    Set<Boolean> maximumSize = ImmutableSet.of(false, true);
    Set<Boolean> weighed = ImmutableSet.of(false, true);

    @SuppressWarnings("unchecked")
    Set<List<Object>> combinations = Sets.cartesianProduct(keyStrengths, valueStrengths,
        expireAfterAccess, expireAfterWrite, maximumSize, weighed);
    return combinations;
  }

  JavaFile makeJavaFile(TypeSpec.Builder nodeFactoryBuilder) {
    return JavaFile.builder(getClass().getPackage().getName(), nodeFactoryBuilder.build())
        .addFileComment("Copyright 2015 Ben Manes. All Rights Reserved.")
        .build();
  }

  TypeSpec.Builder newNodeFactoryBuilder() {
    return TypeSpec.enumBuilder("NodeFactory")
        .addJavadoc("<em>WARNING: GENERATED CODE</em>\n\n")
        .addJavadoc("A factory for cache nodes optimized for a particular configuration.\n")
        .addJavadoc("\n@author ben.manes@gmail.com (Ben Manes)\n")
        .addMethod(newNode()
        .addModifiers(Modifier.ABSTRACT)
        .build());
  }

  MethodSpec.Builder newNode() {
    return MethodSpec.methodBuilder("newNode")
        .addAnnotation(Nonnull.class)
        .addTypeVariable(kTypeVar)
        .addTypeVariable(vTypeVar)
        .addParameter(keySpec)
        .addParameter(valueSpec)
        .returns(Types.parameterizedType(Node.class, kType, vType));
  }

  public static void main(String[] args) throws IOException {
    new NodeGenerator().generate();
  }
}