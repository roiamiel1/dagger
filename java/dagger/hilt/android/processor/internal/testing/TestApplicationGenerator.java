/*
 * Copyright (C) 2020 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.hilt.android.processor.internal.testing;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.hilt.android.processor.internal.AndroidClassNames;
import dagger.hilt.processor.internal.ClassNames;
import dagger.hilt.processor.internal.ComponentNames;
import dagger.hilt.processor.internal.Processors;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Generates an Android Application that holds the Singleton component.
 */
public final class TestApplicationGenerator {
  private static final ClassName TEST_APPLICATION_COMPONENT_MANAGER =
      ClassName.get("dagger.hilt.android.internal.testing", "TestApplicationComponentManager");

  private static final ClassName TEST_APPLICATION_COMPONENT_MANAGER_HOLDER =
      ClassName.get(
          "dagger.hilt.android.internal.testing", "TestApplicationComponentManagerHolder");

  private final ProcessingEnvironment processingEnv;
  private final TypeElement testElement;
  private final ImmutableSet<TypeElement> extraModules;
  private final boolean waitForBindValue;
  private final ClassName testName;
  private final ClassName baseName;
  private final ClassName appName;
  private final ParameterSpec componentManager;
  private final ParameterSpec onComponentReadyRunner;
  private final InternalTestRootMetadata metadata;

  public TestApplicationGenerator(
      ProcessingEnvironment processingEnv,
      InternalTestRootMetadata metadata,
      Set<? extends TypeElement> extraModules,
      boolean waitForBindValue) {
    this.processingEnv = processingEnv;
    this.metadata = metadata;
    this.testElement = metadata.testElement();
    this.testName = metadata.testName();
    this.baseName = metadata.baseName();
    this.appName = metadata.appName();
    this.extraModules = ImmutableSet.copyOf(extraModules);
    this.waitForBindValue = waitForBindValue;

    componentManager =
        ParameterSpec.builder(TEST_APPLICATION_COMPONENT_MANAGER, "componentManager").build();

    onComponentReadyRunner =
        ParameterSpec.builder(ClassNames.ON_COMPONENT_READY_RUNNER, "onComponentReadyRunner")
            .build();
  }

  public void generate() throws IOException {
    TypeSpec.Builder generator =
        TypeSpec.classBuilder(appName)
            .addOriginatingElement(testElement)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(baseName)
            .addSuperinterface(
                ParameterizedTypeName.get(ClassNames.COMPONENT_MANAGER, TypeName.OBJECT))
            .addSuperinterface(TEST_APPLICATION_COMPONENT_MANAGER_HOLDER)
            .addSuperinterface(ClassNames.ON_COMPONENT_READY_RUNNER_HOLDER)
            .addField(getComponentManagerField())
            .addField(getOnComponentReadyRunnerField())
            .addMethod(getOnCreateMethod())
            .addMethod(getComponentManagerMethod())
            .addMethod(getComponentMethod())
            .addMethod(getOnComponentReadyRunnerMethod())
            .addMethod(injectableMethod(testName))
            .addMethod(getInstanceMethod(appName))
            .addField(testName, "testInstance", Modifier.PRIVATE)
            .addMethod(getTestInstanceMethod(testName, "testInstance"))
            .addMethod(bindMethod(testName, appName));

    Processors.addGeneratedAnnotation(
        generator, processingEnv, ClassNames.ROOT_PROCESSOR.toString());

    // For each module, create method and instance field to add/store it until component is built.
    for (TypeElement extraModule : extraModules) {
      generator.addMethod(addTestModuleMethod(appName, ClassName.get(extraModule)));
    }

    JavaFile.builder(appName.packageName(), generator.build())
        .build()
        .writeTo(processingEnv.getFiler());
  }

  /**
   * private TestApplicationComponentManager componentManager =
   *     new TestApplicationComponentManager(
   *        ... see anonymousComponentSupplier(),
   *        ... see requiredModuleList());
   */
  private FieldSpec getComponentManagerField() {
    return FieldSpec
        .builder(componentManager.type, componentManager.name, Modifier.PRIVATE, Modifier.FINAL)
        .initializer(
            "new $T(this, $L, $L, $L)",
            componentManager.type,
            anonymousComponentSupplier(),
            requiredModuleList(),
            waitForBindValue)
        .build();
  }

  /** private final OnComponentReadyRunner onComponentReadyRunner = new OnComponentReadyRunner(); */
  private FieldSpec getOnComponentReadyRunnerField() {
    return FieldSpec.builder(
            onComponentReadyRunner.type,
            onComponentReadyRunner.name,
            Modifier.PRIVATE,
            Modifier.FINAL)
        .initializer("new $T()", onComponentReadyRunner.type)
        .build();
  }

  /**
   * new ComponentSupplier<ApplicationComponent>() {
   *   @Override
   *   public ApplicationComponent get() {
   *     return DaggerApplicationComponent.builder()
   *         .applicationContextModule(new ApplicationContextModule(this))
   *         .fooTestModule1(componentManager.getRegisteredModule(FooTestModule1.class))
   *         ...
   *         .fooTestModuleN(componentManager.getRegisteredModule(FooTestModuleN.class))
   *         .build();
   *   }
   * }
   */
  private TypeSpec anonymousComponentSupplier() {
    String rootPkg = testName.packageName();
    ClassName component =
        ComponentNames.generatedComponent(rootPkg, AndroidClassNames.APPLICATION_COMPONENT);
    return TypeSpec.anonymousClassBuilder("")
        .addSuperinterface(ClassNames.COMPONENT_SUPPLIER)
        .addMethod(
            MethodSpec.methodBuilder("get")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.OBJECT)
                .addStatement(
                    "return $T.builder()\n"
                        + ".applicationContextModule(new $T($T.this))\n"
                        + "$L"
                        + ".build()",
                    Processors.prepend(Processors.getEnclosedClassName(component), "Dagger"),
                    ClassNames.APPLICATION_CONTEXT_MODULE,
                    appName,
                    extraModules
                        .stream()
                        .map(ClassName::get)
                        .map(
                            className ->
                                CodeBlock.of(
                                        ".$L($N.getRegisteredModule($T.class))",
                                        Processors.upperToLowerCamel(className.simpleName()),
                                        componentManager,
                                        className)
                                    .toString())
                        .collect(Collectors.joining("\n")))
                .build())
        .build();
  }

  /**
   * ImmutableSet.of(
   *     FooTest.NonStaticModule1.class,
   *     FooTest.NonStaticModule2.class,
   *     ...)
   */
  private CodeBlock requiredModuleList() {
    return CodeBlock.of("$T.of($L)",
        ClassName.get(ImmutableSet.class),
        extraModules.stream()
            .map(t -> Joiner.on(".").join(ClassName.get(t).simpleNames()) + ".class")
            .collect(Collectors.joining(",\n\t")));
  }

  /**
   * {@literal @Override}
   * public void onCreate() {
   *   super.onCreate();
   *   OnComponentReadyRunner.addListener(
   *       this,
   *       Hilt_XXX_Application_Injector.class,
   *       (Hilt_XXX_Application_Injector injector) -> injector.inject(this));
   * }
   */
  private MethodSpec getOnCreateMethod() {
    return MethodSpec.methodBuilder("onCreate")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .addStatement("super.onCreate()")
        .addStatement(
            "$T.addListener(this, $T.class, ($T injector) -> injector.inject(this))",
            ClassNames.ON_COMPONENT_READY_RUNNER,
            metadata.injectorClassName(),
            metadata.injectorClassName())
        .build();
  }

  private MethodSpec getComponentMethod() {
    return MethodSpec.methodBuilder("generatedComponent")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(TypeName.OBJECT)
        .addStatement("return $N.generatedComponent()", componentManager)
        .build();
  }

  private MethodSpec getComponentManagerMethod() {
    return MethodSpec.methodBuilder("componentManager")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        .returns(TypeName.OBJECT)
        .addStatement("return $N", componentManager)
        .build();
  }

  /**
   * {@literal @Override}
   * public OnComponentReadyRunner getOnComponentReadyRunner() {
   *   return onComponentReadyRunner;
   * }
   */
  private MethodSpec getOnComponentReadyRunnerMethod() {
    return MethodSpec.methodBuilder("getOnComponentReadyRunner")
        .addAnnotation(Override.class)
        .addModifiers(Modifier.PUBLIC)
        .returns(ClassNames.ON_COMPONENT_READY_RUNNER)
        .addStatement("return $N", onComponentReadyRunner)
        .build();
  }

  private MethodSpec injectableMethod(ClassName testName) {
    String varName = Processors.upperToLowerCamel(testName.simpleName());
    MethodSpec.Builder builder =
        MethodSpec.methodBuilder("inject")
            .addJavadoc("Performs member injection of ApplicationComponent bindings.")
            .addParameter(testName, varName)
            .addAnnotation(
                AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build());

    builder.addStatement(
        "(($T) this.generatedComponent()).injectTest($L)",
        ParameterizedTypeName.get(ClassNames.TEST_INJECTOR, testName),
        varName);
    return builder.build();
  }

  private static MethodSpec getInstanceMethod(ClassName appName) {
    return MethodSpec.methodBuilder("get")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addJavadoc("A convenience method for getting an instance of $T during testing.", appName)
        .addStatement(
            "return ($T) $T.getApplicationContext()",
            appName,
            AndroidClassNames.APPLICATION_PROVIDER)
        .returns(appName)
        .build();
  }

  private static MethodSpec addTestModuleMethod(ClassName app, ClassName module) {
    String fieldName = Processors.upperToLowerCamel(module.simpleName());
    return MethodSpec.methodBuilder("add" + module.simpleName())
        .addModifiers(Modifier.PUBLIC)
        .addParameter(module, fieldName)
        .addStatement("componentManager.registerModule($T.class, $L)", module, fieldName)
        .addStatement("return this")
        .returns(app)
        .build();
  }

  private static MethodSpec getTestInstanceMethod(ClassName testClassName, String testParamName) {
    return MethodSpec.methodBuilder("getTestInstance")
        .returns(testClassName)
        .addStatement("$T.checkState($N != null, $S)",
            ClassNames.PRECONDITIONS, testParamName,
            "The test instance has not been set. Did you forget to call #bind()?")
        .addStatement("return $N", testParamName)
        .build();
  }

  private static MethodSpec bindMethod(ClassName testName, ClassName appName) {
    String varName = Processors.upperToLowerCamel(testName.simpleName());
    return MethodSpec.methodBuilder("bind")
        .addJavadoc("Stores an instance of {@link $T} to bind its members to providers.", testName)
        .returns(appName)
        .addParameter(testName, varName)
        .addStatement("testInstance = $L", varName)
        .addStatement("componentManager.setBindValueCalled()")
        .addStatement("return this")
        .build();
  }
}
