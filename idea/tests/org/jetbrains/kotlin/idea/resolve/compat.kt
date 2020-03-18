/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.resolve

import com.intellij.openapi.project.Project
import com.intellij.pom.tree.TreeAspect
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.idea.caches.trackers.KotlinCodeBlockModificationListener

internal fun createKotlinCodeBlockModificationListener(project: Project, treeAspect: TreeAspect) =
    KotlinCodeBlockModificationListener(
        PsiModificationTracker.SERVICE.getInstance(project),
        project,
        treeAspect
    )