package com.ycode.app.ui.providers

import android.content.Context
import android.graphics.drawable.PictureDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import com.caverock.androidsvg.SVG

class ProviderIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    init {
        scaleType = ScaleType.FIT_CENTER
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setProvider(providerId: String) {
        runCatching {
            val svg = SVG.getFromAsset(context.assets, "models/$providerId.svg")
            setImageDrawable(PictureDrawable(svg.renderToPicture()))
        }.onFailure {
            setImageResource(com.ycode.app.R.drawable.ic_launcher)
        }
    }
}
