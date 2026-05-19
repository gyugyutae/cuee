package com.cuee.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.cuee.domain.scoring.Bounds
import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot

interface AccessibilitySnapshotMapper {
    fun map(root: AccessibilityNodeInfo?): ScreenSnapshot
}

class DefaultAccessibilitySnapshotMapper(
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AccessibilitySnapshotMapper {
    override fun map(root: AccessibilityNodeInfo?): ScreenSnapshot {
        if (root == null) {
            return ScreenSnapshot(packageName = "", nodes = emptyList(), capturedAt = clock())
        }

        val nodes = mutableListOf<ScreenNode>()
        visit(
            node = root,
            depth = 0,
            path = "root",
            parentHint = null,
            output = nodes
        )

        return ScreenSnapshot(
            packageName = root.packageName?.toString().orEmpty(),
            nodes = nodes,
            capturedAt = clock()
        )
    }

    private fun visit(
        node: AccessibilityNodeInfo,
        depth: Int,
        path: String,
        parentHint: String?,
        output: MutableList<ScreenNode>
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        output += ScreenNode(
            id = node.viewIdResourceName ?: path,
            text = node.text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            className = node.className?.toString()?.takeIf { it.isNotBlank() },
            packageName = node.packageName?.toString()?.takeIf { it.isNotBlank() },
            bounds = Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            depth = depth,
            parentHint = parentHint
        )

        val childParentHint = node.hintText()
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                visit(
                    node = child,
                    depth = depth + 1,
                    path = "$path/$index",
                    parentHint = childParentHint,
                    output = output
                )
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    private fun AccessibilityNodeInfo.hintText(): String? {
        return listOfNotNull(
            text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription?.toString()?.takeIf { it.isNotBlank() },
            className?.toString()?.takeIf { it.isNotBlank() }
        ).joinToString(separator = " ").takeIf { it.isNotBlank() }
    }
}
