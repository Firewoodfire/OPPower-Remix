package com.oppower.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.itos.coui.shape.CouiShape
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.oppower.R

/**
 * 关于页的封面。
 *
 * 结构和 COUI 官方 demo 的 `DemoAboutHero` 一致：`TextureView` 播 `res/raw/about_hero.mp4`
 * （4 秒、1080×1080、无声），上面压一张首帧 `ImageView` 做占位，视频真正出画后再隐藏，
 * 避免起播瞬间的黑底。暂停、播完、销毁都切到末帧静态图，这也是 demo 的处理方式。
 *
 * 与 demo 的差别只有两处：不引 demo 的 `about_hero_logo` 位图（那是 ColorOS 的商标素材），
 * 品牌字改用 Compose 的 `Text` 叠在 `AndroidView` 之上；尺寸和圆角沿用 demo 的
 * 左右 16dp、高 328dp、`CouiShape(28.dp, 3f)`。
 */
@Composable
internal fun AboutHero(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp)
            .height(328.dp)
            .clip(CouiShape(28.dp, 3f)),
    ) {
        AndroidView(
            factory = { HeroVideoView(it) },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.release() },
        )
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "OPPower",
                kind = CouiTextKind.HomeTitle,
                color = Color.White,
            )
            Text(
                text = "ColorOS 亮屏快充控制",
                kind = CouiTextKind.ItemSummary,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = "基于 LLPower 二改得来",
                kind = CouiTextKind.GroupFooter,
                color = Color.White.copy(alpha = 0.66f),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Text(
            text = "v${com.oppower.BuildConfig.VERSION_NAME}",
            kind = CouiTextKind.GroupFooter,
            color = Color.White.copy(alpha = 0.66f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 14.dp),
        )
    }
}

private class HeroVideoView(context: Context) : FrameLayout(context),
    TextureView.SurfaceTextureListener, DefaultLifecycleObserver {

    private val texture = TextureView(context)
    private val placeholder = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageResource(R.drawable.about_hero_first)
    }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var owner: LifecycleOwner? = null
    private var finished = false

    init {
        // 深色主题下系统会把普通 View 强制反色，视频和占位图都会被拉低亮度。
        isForceDarkAllowed = false
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = this
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        owner = lifecycleOwner
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onResume(owner: LifecycleOwner) {
        texture.surfaceTexture?.let { start(it) }
    }

    override fun onPause(owner: LifecycleOwner) {
        showLastFrame()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        start(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        showLastFrame()
        return true
    }

    fun release() {
        owner?.lifecycle?.removeObserver(this)
        owner = null
        showLastFrame()
    }

    private fun start(surfaceTexture: SurfaceTexture) {
        if (finished || player != null ||
            owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true
        ) return
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        surface = Surface(surfaceTexture)
        try {
            resources.openRawResourceFd(R.raw.about_hero).use {
                mediaPlayer.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            mediaPlayer.setSurface(surface)
            mediaPlayer.isLooping = false
            mediaPlayer.setVolume(0f, 0f)
            mediaPlayer.setOnPreparedListener {
                if (player === it && !finished) it.start()
            }
            mediaPlayer.setOnInfoListener { current, what, _ ->
                if (player === current && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    placeholder.visibility = GONE
                }
                false
            }
            mediaPlayer.setOnCompletionListener { finished = true }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.e("AboutHero", "封面动画播放失败: what=$what, extra=$extra")
                showLastFrame()
                true
            }
            mediaPlayer.prepareAsync()
        } catch (error: Exception) {
            Log.e("AboutHero", "封面动画初始化失败", error)
            showLastFrame()
        }
    }

    private fun showLastFrame() {
        finished = true
        placeholder.setImageResource(R.drawable.about_hero_last)
        placeholder.visibility = VISIBLE
        player?.apply {
            setOnPreparedListener(null)
            setOnInfoListener(null)
            setOnCompletionListener(null)
            setOnErrorListener(null)
            release()
        }
        player = null
        surface?.release()
        surface = null
    }
}
