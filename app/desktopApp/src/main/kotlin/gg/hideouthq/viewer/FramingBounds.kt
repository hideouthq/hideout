package gg.hideouthq.viewer

import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import kotlin.math.max

internal fun framingComponents(boxes: List<BoundingBox>, skeleton: BoundingBox): BooleanArray {
    if (!skeleton.isValid) return BooleanArray(boxes.size) { true }
    val sizes = boxes.map { it.getDimensions(Vector3()).len2() }
    val selected = BooleanArray(boxes.size)
    val subject = BoundingBox(skeleton)
    val dimensions = Vector3()
    do {
        var changed = false
        boxes.forEachIndexed { i, box ->
            if (!selected[i]) {
                val x = max(0f, max(subject.min.x - box.max.x, box.min.x - subject.max.x))
                val y = max(0f, max(subject.min.y - box.max.y, box.min.y - subject.max.y))
                val z = max(0f, max(subject.min.z - box.max.z, box.min.z - subject.max.z))
                if (x * x + y * y + z * z <= max(subject.getDimensions(dimensions).len2(), sizes[i])) {
                    selected[i] = true
                    subject.ext(box)
                    changed = true
                }
            }
        }
    } while (changed)
    return if (selected.any { it }) selected else BooleanArray(boxes.size) { true }
}
