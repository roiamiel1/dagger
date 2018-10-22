/*
 * Copyright (C) 2014 The Dagger Authors.
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

package dagger.internal.codegen;

import static com.google.auto.common.GeneratedAnnotations.generatedAnnotation;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Throwables;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import java.util.Optional;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;

/**
 * A template class that provides a framework for properly handling IO while generating source files
 * from an annotation processor.  Particularly, it makes a best effort to ensure that files that
 * fail to write successfully are deleted.
 *
 * @param <T> The input type from which source is to be generated.
 */
abstract class SourceFileGenerator<T> {
  private static final String GENERATED_COMMENTS = "https://google.github.io/dagger";

  private final Filer filer;
  private final Elements elements;
  private final SourceVersion sourceVersion;

  SourceFileGenerator(Filer filer, Elements elements, SourceVersion sourceVersion) {
    this.filer = checkNotNull(filer);
    this.elements = checkNotNull(elements);
    this.sourceVersion = checkNotNull(sourceVersion);
  }

  SourceFileGenerator(SourceFileGenerator<T> delegate) {
    this(delegate.filer, delegate.elements, delegate.sourceVersion);
  }

  /**
   * Generates a source file to be compiled for {@code T}. Writes any generation exception to {@code
   * messager} and does not throw.
   */
  void generate(T input, Messager messager) {
    try {
      generate(input);
    } catch (SourceFileGenerationException e) {
      e.printMessageTo(messager);
    }
  }

  /** Generates a source file to be compiled for {@code T}. */
  void generate(T input) throws SourceFileGenerationException {
    ClassName generatedTypeName = nameGeneratedType(input);
    Optional<TypeSpec.Builder> type = write(generatedTypeName, input);
    if (!type.isPresent()) {
      return;
    }
    try {
      buildJavaFile(generatedTypeName, input, type.get()).writeTo(filer);
    } catch (Exception e) {
      // if the code above threw a SFGE, use that
      Throwables.propagateIfPossible(e, SourceFileGenerationException.class);
      // otherwise, throw a new one
      throw new SourceFileGenerationException(
          Optional.empty(), e, originatingElement(input));
    }
  }

  private JavaFile buildJavaFile(
      ClassName generatedTypeName, T input, TypeSpec.Builder typeSpecBuilder) {
    typeSpecBuilder.addOriginatingElement(originatingElement(input));
    Optional<AnnotationSpec> generatedAnnotation =
        generatedAnnotation(elements, sourceVersion)
            .map(
                annotation ->
                    AnnotationSpec.builder(ClassName.get(annotation))
                        .addMember("value", "$S", "dagger.internal.codegen.ComponentProcessor")
                        .addMember("comments", "$S", GENERATED_COMMENTS)
                        .build());
    generatedAnnotation.ifPresent(typeSpecBuilder::addAnnotation);
    JavaFile.Builder javaFileBuilder =
        JavaFile.builder(generatedTypeName.packageName(), typeSpecBuilder.build())
            .skipJavaLangImports(true);
    if (!generatedAnnotation.isPresent()) {
      javaFileBuilder.addFileComment("Generated by Dagger ($L).", GENERATED_COMMENTS);
    }
    return javaFileBuilder.build();
  }

  /**
   * Implementations should return the {@link ClassName} for the top-level type to be generated.
   */
  abstract ClassName nameGeneratedType(T input);

  /** Returns the originating element of the generating type. */
  abstract Element originatingElement(T input);

  /**
   * Returns a {@link TypeSpec.Builder type} to be generated for {@code T}, or {@link
   * Optional#empty()} if no file should be generated.
   */
  // TODO(ronshapiro): write() makes more sense in JavaWriter where all writers are mutable.
  // consider renaming to something like typeBuilder() which conveys the mutability of the result
  abstract Optional<TypeSpec.Builder> write(ClassName generatedTypeName, T input);
}
