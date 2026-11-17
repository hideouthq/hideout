package gg.hideouthq.viewer

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera as GdxCamera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Cubemap
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.model.Node
import com.badlogic.gdx.graphics.g3d.model.NodePart
import com.badlogic.gdx.graphics.g3d.utils.DefaultTextureBinder
import com.badlogic.gdx.graphics.g3d.utils.RenderContext
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import com.badlogic.gdx.utils.Disposable
import gg.hideouthq.core.model.SceneLighting
import net.mgsx.gltf.loaders.exceptions.GLTFUnsupportedException
import net.mgsx.gltf.loaders.glb.BinaryDataFileResolver
import net.mgsx.gltf.loaders.shared.GLTFLoaderBase
import net.mgsx.gltf.scene3d.animation.AnimationControllerHack
import net.mgsx.gltf.scene3d.attributes.PBRCubemapAttribute
import net.mgsx.gltf.scene3d.attributes.PBRTextureAttribute
import net.mgsx.gltf.scene3d.lights.DirectionalLightEx
import net.mgsx.gltf.scene3d.scene.Scene
import net.mgsx.gltf.scene3d.scene.SceneAsset
import net.mgsx.gltf.scene3d.scene.SceneManager
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider
import net.mgsx.gltf.scene3d.utils.IBLBuilder
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal class UnsupportedScene(message: String, cause: Throwable? = null) : Exception(message, cause)

data class FrameRequest(
    val width: Int,
    val height: Int,
    val clip: String?,
    val timeSeconds: Float,
    val camera: Camera,
    val lighting: SceneLighting,
    val framing: CameraFraming = CameraFraming.FitView,
)

sealed interface CameraFraming {
    data object FitView : CameraFraming
    data class Reference(val azimuthDegrees: Float, val pitchDegrees: Float = Camera.StartPitchDegrees) : CameraFraming
}

class RenderedFrame(val request: FrameRequest, val image: Image, private val open: AtomicInteger) : AutoCloseable {
    @Volatile var drawn = 0L
    private val closed = AtomicBoolean()

    init {
        open.incrementAndGet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        image.close()
        open.decrementAndGet()
    }
}

class SceneRenderer private constructor(
    private val asset: SceneAsset,
    private val manager: SceneManager,
    private val palettes: BonePalettes,
    private val context: RenderContext,
    private val sampler: PoseSampler,
    private val lights: Lights,
    private val framings: Framings,
    private val liveScenes: AtomicInteger,
    private val openFrames: AtomicInteger,
    private val maxSide: Int,
) : Disposable {
    private val camera = OrthographicCamera()
    private val downsample = SpriteBatch(1)
    private var targets: Targets? = null
    private var disposed = false

    fun render(request: FrameRequest): RenderedFrame = try {
        draw(request)
    } catch (t: Throwable) {
        clearGlErrors()
        throw t
    }

    private fun draw(request: FrameRequest): RenderedFrame {
        val (scene, target) = targets(request.width, request.height)
        val framing = framings[request.clip]
        sampler.sample(request.clip, request.timeSeconds)
        aim(request, framing)
        manager.update(0f)
        palettes.upload()
        scene.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        context.begin()
        manager.render()
        context.end()
        scene.end()
        target.begin()
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        downsample.projectionMatrix.setToOrtho2D(0f, 0f, request.width.toFloat(), request.height.toFloat())
        downsample.disableBlending()
        downsample.begin()
        downsample.draw(scene.colorBufferTexture, 0f, 0f, request.width.toFloat(), request.height.toFloat(), 0f, 0f, 1f, 1f)
        downsample.end()
        Gdx.gl.glFinish()
        val size = request.width * request.height * 4
        val pixels = Data.makeUninitialized(size)
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1)
        Gdx.gl.glReadPixels(0, 0, request.width, request.height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, MemoryUtil.memByteBuffer(pixels.writableData(), size))
        target.end()
        val error = Gdx.gl.glGetError()
        if (error != GL20.GL_NO_ERROR) {
            pixels.close()
            throw IllegalStateException("OpenGL error 0x${error.toString(16)} while drawing")
        }
        val info = ImageInfo(request.width, request.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val image = pixels.use { Image.makeRaster(info, it, request.width * 4) }
        return RenderedFrame(request, image, openFrames)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        liveScenes.decrementAndGet()
        listOfNotNull(targets, downsample, manager, palettes, lights, asset).forEach { it.dispose() }
    }

    private fun targets(width: Int, height: Int): Targets {
        targets?.takeIf { it.target.width == width && it.target.height == height }?.let { return it }
        targets?.let { old ->
            targets = null
            old.dispose()
        }
        val scale = if (max(width, height) * Supersampling <= maxSide) Supersampling else 1
        val scene = GLFrameBuffer.FrameBufferBuilder(width * scale, height * scale).run {
            addBasicColorTextureAttachment(Pixmap.Format.RGBA8888)
            addDepthRenderBuffer(DepthComponent24)
            build()
        }
        scene.colorBufferTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        val target = GLFrameBuffer.FrameBufferBuilder(width, height).run {
            addBasicColorTextureAttachment(Pixmap.Format.RGBA8888)
            build()
        }
        return Targets(scene, target).also { targets = it }
    }

    private data class Targets(val scene: FrameBuffer, val target: FrameBuffer) : Disposable {
        override fun dispose() {
            scene.dispose()
            target.dispose()
        }
    }

    private fun aim(request: FrameRequest, framing: Framing) {
        val view = request.camera
        val aspect = request.width.toFloat() / request.height
        val azimuth = Math.toRadians(view.azimuthDegrees.toDouble())
        val elevation = tan(Math.toRadians(view.pitchDegrees.toDouble())).toFloat()
        val toCamera = Vector3(cos(azimuth).toFloat(), elevation, -sin(azimuth).toFloat()).nor()
        camera.direction.set(toCamera).scl(-1f)
        camera.up.set(Vector3.Y)
        camera.normalizeUp()
        val right = Vector3(camera.direction).crs(camera.up).nor()
        val fitted = when (val policy = request.framing) {
            CameraFraming.FitView -> framing.fitted(aspect, camera.up, right)
            is CameraFraming.Reference -> {
                val angle = Math.toRadians(policy.azimuthDegrees.toDouble())
                val pitch = tan(Math.toRadians(policy.pitchDegrees.toDouble())).toFloat()
                val direction = Vector3(-cos(angle).toFloat(), -pitch, sin(angle).toFloat()).nor()
                val referenceRight = Vector3(direction).crs(Vector3.Y).nor()
                val referenceUp = Vector3(referenceRight).crs(direction).nor()
                framing.fitted(aspect, referenceUp, referenceRight)
            }
        }
        val visible = fitted / view.zoom
        camera.viewportHeight = visible
        camera.viewportWidth = visible * aspect
        val target = Vector3(framing.center.x, framing.bottom + (FeetLine - 0.5f) * fitted, framing.center.z)
        val distance = framing.radius * 4 + 1
        camera.position.set(target).mulAdd(toCamera, distance)
        camera.position.mulAdd(right, -view.panX * fitted).mulAdd(camera.up, view.panY * fitted)
        camera.near = 0.001f
        camera.far = distance * 2 + framing.radius * 2
        camera.update()
        manager.camera = camera
        lights.aim(camera.direction, camera.up, request.lighting)
    }

    /** The clips are the instance's own copies, which drive its nodes; the asset's originals would move the shared model instead. */
    private class PoseSampler(instance: ModelInstance) : AnimationControllerHack(instance) {
        private val clips = instance.animations.associateBy { it.id }
        private val nodes = buildList {
            val stack = ArrayDeque<Node>(instance.nodes.toList())
            while (stack.isNotEmpty()) stack.removeLast().also { add(it) }.children.forEach(stack::addLast)
        }

        fun sample(clip: String?, seconds: Float) {
            val animation = clip?.let { clips[it] ?: throw IllegalArgumentException("The scene has no clip $it") }
            nodes.forEach { it.isAnimated = false }
            if (animation == null) target.calculateTransforms() else applyAnimation(animation, seconds.coerceIn(0f, animation.duration))
        }
    }

    private class Lights(manager: SceneManager) : Disposable {
        private val key = DirectionalLightEx()
        private val fill = DirectionalLightEx()
        private val rim = DirectionalLightEx()
        private val brdf = Texture(Gdx.files.classpath("net/mgsx/gltf/shaders/brdfLUT.png"))
        private val diffuse: Cubemap
        private val specular: Cubemap

        init {
            manager.environment.add(key)
            manager.environment.add(fill)
            manager.environment.add(rim)
            val ibl = IBLBuilder.createOutdoor(DirectionalLightEx().set(Color.WHITE, Vector3(-0.4f, -1f, -0.5f)))
            diffuse = ibl.buildIrradianceMap(IrradianceSize)
            specular = ibl.buildRadianceMap(RadianceMipLevels)
            ibl.dispose()
            manager.environment.set(PBRTextureAttribute.createBRDFLookupTexture(brdf))
            manager.environment.set(PBRCubemapAttribute.createDiffuseEnv(diffuse))
            manager.environment.set(PBRCubemapAttribute.createSpecularEnv(specular))
        }

        fun aim(forward: Vector3, up: Vector3, lighting: SceneLighting) {
            val right = Vector3(forward).crs(up).nor()
            val (keyIntensity, fillIntensity, rimIntensity) = when (lighting) {
                SceneLighting.Soft -> Triple(2.2f, 1.2f, 0f)
                SceneLighting.Studio -> Triple(3.0f, 0.8f, 0f)
                SceneLighting.Dramatic -> Triple(4.0f, 0.6f, 0.9f)
            }
            key.set(Color.WHITE, Vector3(forward).mulAdd(up, -0.8f).mulAdd(right, 0.6f).nor(), keyIntensity)
            fill.set(Color.WHITE, Vector3(forward).mulAdd(up, -0.2f).mulAdd(right, -0.9f).nor(), fillIntensity)
            rim.set(Color.WHITE, Vector3(forward).scl(-1f).mulAdd(up, -0.4f).nor(), rimIntensity)
        }

        override fun dispose() = listOf(brdf, diffuse, specular).forEach { it.dispose() }
    }

    private class Framing(sizingBox: BoundingBox, val bottom: Float) {
        val center: Vector3 = sizingBox.getCenter(Vector3())
        private val dimensions = sizingBox.getDimensions(Vector3())
        private val floorOffset = bottom - sizingBox.min.y
        val radius = dimensions.len() / 2

        fun fitted(aspect: Float, up: Vector3, right: Vector3): Float {
            val halfDepth = (abs(up.x) * dimensions.x + abs(up.z) * dimensions.z) / 2
            val above = (dimensions.y - floorOffset) * up.y + halfDepth
            val below = floorOffset * up.y + halfDepth
            val rise = (FeetLine - 0.5f) * up.y
            val across = abs(right.x) * dimensions.x + abs(right.z) * dimensions.z
            return maxOf(above / (0.5f - HeadLine + rise), below / (0.5f - HeadLine - rise), across / (FootprintFill * aspect))
        }
    }

    private class Framings(private val instance: ModelInstance, private val sampler: PoseSampler, clips: List<String>) {
        private val bindPose = BoundingBox().inf()
        private val shapes: List<Pair<Node, PartShape>>

        init {
            sampler.sample(null, 0f)
            val skeleton = BoundingBox().inf()
            val position = Vector3()
            val components = buildList {
                fun collect(node: Node) {
                    node.parts.filter { it.enabled }.forEach { part ->
                        part.invBoneBindTransforms?.let { joints ->
                            for (i in 0 until joints.size) skeleton.ext(joints.keys[i].globalTransform.getTranslation(position))
                        }
                        PartShape.components(part).forEach { add(node to it) }
                    }
                    node.children.forEach(::collect)
                }
                instance.nodes.forEach(::collect)
            }
            val boxes = components.map { (node, shape) -> BoundingBox().inf().also { shape.extend(node, it) } }
            val selected = framingComponents(boxes, skeleton)
            boxes.forEachIndexed { i, box -> if (selected[i]) bindPose.ext(box) }
            shapes = components.filterIndexed { i, _ -> selected[i] }
        }

        private val fitted = (clips + null).associateWith { clip -> lazy(LazyThreadSafetyMode.NONE) { fit(clip) } }

        operator fun get(clip: String?): Framing = (fitted[clip] ?: throw IllegalArgumentException("The scene has no clip $clip")).value

        private fun fit(clip: String?): Framing {
            val sampled = sampledBoxWithoutMorphOffsets(clip)
            val floor = bindPose.min.y
            val drawn = sampled.min.y
            val resized = sampled.isValid && fitted(sampled.height, footprint(sampled), 1f).isFinite() && (
                abs(sampled.width - bindPose.width) > BindFitToleranceExtentFraction * bindPose.width ||
                    abs(sampled.height - bindPose.height) > BindFitToleranceExtentFraction * bindPose.height ||
                    abs(sampled.depth - bindPose.depth) > BindFitToleranceExtentFraction * bindPose.depth
                )
            val line = if (resized) drawn else if (!drawn.isFinite() || abs(drawn - floor) <= BindFloorToleranceHeightFraction * bindPose.height) floor else drawn
            return Framing(if (resized) sampled else bindPose, line)
        }

        private fun sampledBoxWithoutMorphOffsets(clip: String?): BoundingBox {
            val duration = clip?.let { instance.getAnimation(it).duration } ?: 0f
            val sampled = BoundingBox().inf()
            for (step in 0..(if (clip == null) 0 else PoseSamples)) {
                sampler.sample(clip, duration * step / PoseSamples)
                shapes.forEach { (node, shape) -> shape.extend(node, sampled) }
            }
            return sampled
        }
    }

    private class OverAlphaContext : RenderContext(DefaultTextureBinder(DefaultTextureBinder.LRU, BonePaletteLayout.TextureUnit + 1)) {
        override fun setBlending(enabled: Boolean, sFactor: Int, dFactor: Int) {
            super.setBlending(enabled, sFactor, dFactor)
            if (enabled) Gdx.gl.glBlendFuncSeparate(sFactor, dFactor, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    companion object {
        private const val DepthComponent24 = 0x81A6
        private const val Supersampling = 2
        private const val FeetLine = 0.88f
        private const val HeadLine = 0.05f
        private const val FootprintFill = 0.9f
        private const val IrradianceSize = 64
        private const val RadianceMipLevels = 8

        fun load(files: SceneContent, gl: GlInfo, liveScenes: AtomicInteger, openFrames: AtomicInteger): SceneRenderer =
            load(files, gl, liveScenes, openFrames, ::PaletteShaders)

        internal fun load(files: SceneContent, gl: GlInfo, liveScenes: AtomicInteger, openFrames: AtomicInteger, shaders: (PBRShaderConfig, BonePalettes) -> PBRShaderProvider): SceneRenderer {
            val scene = files.scene
            val joints = scene.maxJointsPerSkin
            val asset = asset(files)
            val owned = mutableListOf<Disposable>(asset)
            try {
                check(asset.maxBones == joints) { "The model's largest skin has ${asset.maxBones} joints, not $joints" }
                val loaded = asset.animations.associateBy { it.id }
                for (clip in files.clips) {
                    val animation = checkNotNull(loaded[clip.id]) { "The model has no clip ${clip.id}" }
                    check(abs(animation.duration * 1000 - clip.durationMillis) <= 1) { "Clip ${clip.id} lasts ${animation.duration}s, not ${clip.durationMillis}ms" }
                }
                val instance = Scene(asset.scene)
                instance.modelInstance.nodes.forEach { it.hideParts(files.hiddenMeshSuffixes) }
                val palettes = BonePalettes(instance.modelInstance, joints).also { owned += it }
                val weights = 4 * max(1, scene.influenceSets)
                val config = PBRShaderProvider.createDefaultConfig().apply {
                    numBones = joints
                    numBoneWeights = weights
                    numDirectionalLights = 3
                    numPointLights = 0
                    numSpotLights = 0
                }
                val depth = PBRShaderProvider.createDefaultDepthConfig().apply {
                    numBones = joints
                    numBoneWeights = weights
                }
                val colours = shaders(config, palettes)
                val sorter = ModelOrderSorter()
                val manager = SceneManager(colours, PaletteDepthShaders(depth, palettes), sorter).also { owned += it }
                // The manager's own batch shares the providers and holds no GL object of its own, so it is let go, not disposed.
                val context = OverAlphaContext()
                manager.setBatch(ModelBatch(context, colours, sorter))
                manager.addScene(instance)
                val lights = Lights(manager).also { owned += it }
                val sampler = PoseSampler(instance.modelInstance)
                val clips = files.clips.map { it.id }
                val framings = Framings(instance.modelInstance, sampler, clips)
                val first = clips.firstOrNull()
                val renderer = SceneRenderer(asset, manager, palettes, context, sampler, lights, framings, liveScenes, openFrames, gl.maxRenderbufferSize)
                liveScenes.incrementAndGet()
                owned.clear()
                owned += renderer
                renderer.render(FrameRequest(WarmUpSide, WarmUpSide, first, 0f, Camera(0f), SceneLighting.Studio)).close()
                return renderer
            } catch (t: Throwable) {
                owned.asReversed().forEach { runCatching { it.dispose() } }
                throw t
            }
        }

        internal fun asset(files: SceneContent): SceneAsset = try {
            val model = BinaryDataFileResolver().apply { load(files.glb) }
            GLTFLoaderBase(SceneTextures(model.root, files.images)).load(model, false)
        } catch (e: GLTFUnsupportedException) {
            throw UnsupportedScene(e.message ?: "Unsupported glTF", e)
        } catch (e: RuntimeException) {
            throw UnsupportedScene("The glTF loader cannot read this model: $e", e)
        }

        private const val WarmUpSide = 16
        private const val MaxClearedErrors = 32

        private fun clearGlErrors() {
            repeat(MaxClearedErrors) { if (Gdx.gl.glGetError() == GL20.GL_NO_ERROR) return }
        }

        private const val PoseSamples = 8

        private const val BindFloorToleranceHeightFraction = 0.01f
        private const val BindFitToleranceExtentFraction = 0.03f / (FeetLine - HeadLine)

        private fun footprint(box: BoundingBox) = 2 * sqrt((box.width / 2) * (box.width / 2) + (box.depth / 2) * (box.depth / 2))

        private fun fitted(height: Float, footprint: Float, aspect: Float) = max(height / (FeetLine - HeadLine), footprint / (FootprintFill * aspect))

        private class PartShape(private val part: NodePart, private val influences: Int, private val vertices: FloatArray) {
            companion object {
                fun components(part: NodePart): List<PartShape> {
                    val mesh = part.meshPart.mesh
                    val stride = mesh.vertexSize / 4
                    val all = FloatArray(mesh.numVertices * stride).also { mesh.getVertices(it) }
                    val position = mesh.getVertexAttribute(VertexAttributes.Usage.Position).offset / 4
                    val weights = (0 until mesh.vertexAttributes.size()).map { mesh.vertexAttributes.get(it) }
                        .filter { it.usage == VertexAttributes.Usage.BoneWeight }.map { it.offset / 4 }
                    val range = part.meshPart.offset until part.meshPart.offset + part.meshPart.size
                    val drawn = if (mesh.numIndices > 0) {
                        val indices = ShortArray(mesh.numIndices).also { mesh.getIndices(it) }
                        range.map { indices[it].toInt() and 0xFFFF }
                    } else {
                        range.toList()
                    }
                    val parents = IntArray(mesh.numVertices) { it }
                    fun root(vertex: Int): Int {
                        var i = vertex
                        while (parents[i] != i) {
                            parents[i] = parents[parents[i]]
                            i = parents[i]
                        }
                        return i
                    }
                    if (part.meshPart.primitiveType == GL20.GL_TRIANGLES) {
                        for (i in 0 until (drawn.size - 2) step 3) {
                            val first = root(drawn[i])
                            parents[root(drawn[i + 1])] = first
                            parents[root(drawn[i + 2])] = first
                        }
                    }
                    val groups = if (part.meshPart.primitiveType == GL20.GL_TRIANGLES) drawn.distinct().groupBy(::root).values else listOf(drawn.distinct())
                    val size = 3 + 2 * weights.size
                    return groups.map { group ->
                        val vertices = FloatArray(group.size * size)
                        group.forEachIndexed { i, vertex ->
                            val from = vertex * stride
                            all.copyInto(vertices, i * size, from + position, from + position + 3)
                            weights.forEachIndexed { w, weight -> all.copyInto(vertices, i * size + 3 + 2 * w, from + weight, from + weight + 2) }
                        }
                        PartShape(part, weights.size, vertices)
                    }
                }
            }

            fun extend(node: Node, box: BoundingBox) {
                val bones = part.bones
                val point = Vector3()
                val moved = Vector3()
                for (at in vertices.indices step 3 + 2 * influences) {
                    val x = vertices[at]
                    val y = vertices[at + 1]
                    val z = vertices[at + 2]
                    if (bones == null) {
                        point.set(x, y, z).mul(node.globalTransform)
                    } else {
                        point.setZero()
                        for (w in 0 until influences) {
                            val amount = vertices[at + 4 + 2 * w]
                            if (amount == 0f) continue
                            point.mulAdd(moved.set(x, y, z).mul(bones[vertices[at + 3 + 2 * w].toInt()]), amount)
                        }
                    }
                    box.ext(point)
                }
            }
        }

        private fun Node.hideParts(suffixes: List<String>) {
            if (suffixes.any { id.endsWith(it) }) parts.forEach { it.enabled = false }
            children.forEach { it.hideParts(suffixes) }
        }
    }
}

/**
 * gdx-gltf's SceneRenderableSorter groups opaque parts by the hash codes of their shaders and meshes, which differ from
 * one renderer to the next, so two parts with fragments at exactly the same depth won the depth test either way.
 * Nothing here draws a skybox, so its opaque-last hint is not kept.
 */
internal class ModelOrderSorter : RenderableSorter, Comparator<Renderable> {
    private var camera: GdxCamera? = null
    private val first = Vector3()
    private val second = Vector3()

    /** libGDX sorts with TimSort, which keeps the order of parts this compares as equal. */
    override fun sort(camera: GdxCamera, renderables: com.badlogic.gdx.utils.Array<Renderable>) {
        this.camera = camera
        renderables.sort(this)
    }

    override fun compare(o1: Renderable, o2: Renderable): Int {
        val blended = o1.blended()
        if (blended != o2.blended()) return if (blended) 1 else -1
        if (!blended) return 0
        val position = checkNotNull(camera) { "Sorted before a camera was given" }.position
        return Integer.compare(position.dst2(o2.centre(second)).toInt(), position.dst2(o1.centre(first)).toInt())
    }

    private fun Renderable.blended() = (material.get(BlendingAttribute.Type) as BlendingAttribute?)?.blended == true

    private fun Renderable.centre(out: Vector3): Vector3 = when {
        meshPart.center.isZero -> worldTransform.getTranslation(out)
        !worldTransform.hasRotationOrScaling() -> worldTransform.getTranslation(out).add(meshPart.center)
        else -> out.set(meshPart.center).mul(worldTransform)
    }
}
