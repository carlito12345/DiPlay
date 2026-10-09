package com.shilapi.xcertplay

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/** Owns only the Surface wrapper; returning true lets TextureView release its texture. */
internal class ClusterVideoTexture(
    context: Context,
    private val bufferWidth: Int = 1920,
    private val bufferHeight: Int = 720,
    private val onFrame: () -> Unit = {},
    private val onPresented: () -> Unit = {},
    private val onSurface: (Surface?) -> Unit,
) :
    TextureView(context), java.io.Closeable {
    constructor(context: Context, onSurface: (Surface?) -> Unit) : this(context, 1920, 720, {}, {}, onSurface)
    private var output: Surface? = null
    private val pictureBinding = CarPlayPicture.Binding(this)
    init {
        isOpaque = false
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                releaseOutput()
                texture.setDefaultBufferSize(bufferWidth, bufferHeight)
                output = Surface(texture).also(onSurface)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { onFrame(); onPresented() }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releaseOutput()
                return true
            }
        }
    }
    private fun releaseOutput() {
        if (output == null) return
        onSurface(null)
        output?.release()
        output = null
    }
    override fun close() {
        pictureBinding.close()
        surfaceTextureListener = null
        releaseOutput()
    }
}
