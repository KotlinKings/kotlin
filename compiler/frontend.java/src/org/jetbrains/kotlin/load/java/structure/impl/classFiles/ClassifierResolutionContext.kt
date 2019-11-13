/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.load.java.structure.impl.classFiles

import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.JavaClassifier
import org.jetbrains.kotlin.load.java.structure.JavaClassifierInfo
import org.jetbrains.kotlin.load.java.structure.JavaTypeParameter
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.util.javaslang.ImmutableHashMap
import org.jetbrains.kotlin.util.javaslang.ImmutableMap
import org.jetbrains.kotlin.util.javaslang.getOrNull
import org.jetbrains.kotlin.utils.SmartList

typealias ClassIdToJavaClass = (ClassId) -> JavaClass?

class ClassifierResolutionContext private constructor(
    private val classesByQName: ClassIdToJavaClass,
    // Note that this data is fully mutable and its correctness is based on the assumption
    // that nobody starts resolving classifier until type parameters and inner classes are initialized.
    // Currently it's implemented through laziness in the PlainJavaClassifierType.
    private var typeParameters: ImmutableMap<String, JavaTypeParameter>,
    private var innerClasses: ImmutableMap<String, InnerClassInfo>
) {
    constructor(classesByQName: ClassIdToJavaClass) : this(classesByQName, ImmutableHashMap.empty(), ImmutableHashMap.empty())

    internal data class Result(val classifier: JavaClassifier?, val qualifiedName: String)

    private class InnerClassInfo(val outerInternalName: String, val simpleName: String)

    internal fun addInnerClass(innerInternalName: String, outerInternalName: String, simpleName: String) {
        innerClasses = innerClasses.put(innerInternalName, InnerClassInfo(outerInternalName, simpleName))
    }

    internal fun addTypeParameters(newTypeParameters: Collection<JavaTypeParameter>) {
        if (newTypeParameters.isEmpty()) return

        typeParameters =
                newTypeParameters
                    .fold(typeParameters) { acc, typeParameter ->
                        acc.put(typeParameter.name.identifier, typeParameter)
                    }
    }

    private fun resolveClass(classId: ClassId) = Result(classesByQName(classId), classId.asSingleFqName().asString())
    internal fun resolveTypeParameter(name: String) = Result(typeParameters.getOrNull(name), name)

    internal fun copyForMember() = ClassifierResolutionContext(classesByQName, typeParameters, innerClasses)

    internal fun mapInternalNameToClassId(internalName: String): ClassId {
        if ('$' in internalName) {
            val innerClassInfo = innerClasses.getOrNull(internalName)
            if (innerClassInfo != null && Name.isValidIdentifier(innerClassInfo.simpleName)) {
                val outerClassId = mapInternalNameToClassId(innerClassInfo.outerInternalName)
                return outerClassId.createNestedClassId(Name.identifier(innerClassInfo.simpleName))
            }
        }

        return ClassId.topLevel(FqName(internalName.replace('/', '.')))
    }

    internal fun mapInternalNameToClassifierInfo(internalName: String): JavaClassifierInfo {
        return JavaClassifierInfo.ClassReference(mapInternalNameToClassId(internalName))
    }

    // See com.intellij.psi.impl.compiled.StubBuildingVisitor.GUESSING_MAPPER
    private fun convertNestedClassInternalNameWithSimpleHeuristic(internalName: String): ClassId? {
        val splitPoints = SmartList<Int>()
        for (p in 0 until internalName.length) {
            val c = internalName[p]
            if (c == '$' && p > 0 && internalName[p - 1] != '/' && p < internalName.length - 1 && internalName[p + 1] != '$') {
                splitPoints.add(p)
            }
        }

        if (splitPoints.isEmpty()) return null

        val substrings = (listOf(-1) + splitPoints).zip(splitPoints + internalName.length).map { (from, to) ->
            internalName.substring(from + 1, to)
        }

        val outerFqName = FqName(substrings[0].replace('/', '.'))
        val packageFqName = outerFqName.parent()
        val relativeName = FqName(outerFqName.shortName().asString() + "." + substrings.subList(1, substrings.size).joinToString("."))

        return ClassId(packageFqName, relativeName, false)
    }

    internal fun resolveByInternalName(internalName: String): Result {
        val result = resolveClass(mapInternalNameToClassId(internalName))
        if (result.classifier == null && '$' in internalName) {
            // Class files generated by some (non-javac) compilers lack the InnerClasses attribute which would make it possible to
            // unambiguously determine how to parse a particular internal name found in the class file. For example, see
            // https://issues.apache.org/jira/browse/GROOVY-8863. In this case, we're trying to treat all dollar characters in the class
            // name as nested class separators, lookup the class with that ClassId, and see if its InnerClasses attribute confirms our
            // suspicion that this class was in fact nested.
            val heuristicName = convertNestedClassInternalNameWithSimpleHeuristic(internalName)
            if (heuristicName != null) {
                val heuristicResult = resolveClass(heuristicName)
                if (heuristicResult.classifier is BinaryJavaClass) {
                    val realName = heuristicResult.classifier.context.mapInternalNameToClassId(internalName)
                    if (heuristicName == realName) {
                        return heuristicResult
                    }
                }
            }
        }

        return result
    }
}
