package com.cuee.domain.scoring

data class ScreenNode(
    val id: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val packageName: String?,
    val bounds: Bounds,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val depth: Int,
    val parentHint: String?
)
