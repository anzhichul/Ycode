package com.ycode.app.ui.preview

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

class AndroidXmlRenderer(private val context: Context) {
    val logs = mutableListOf<String>()
    private val density = context.resources.displayMetrics.density

    fun render(input: InputStream): View {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            setExpandEntityReferences(false)
        }
        val root = factory.newDocumentBuilder().parse(input).documentElement
        return build(root)
    }

    private fun build(element: Element): View {
        val name = element.tagName.substringAfterLast('.')
        val view: View = when (name) {
            "LinearLayout" -> LinearLayout(context).apply { orientation = if (attr(element, "orientation") == "horizontal") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
            "FrameLayout", "ConstraintLayout", "CoordinatorLayout", "DrawerLayout" -> FrameLayout(context).also { if (name != "FrameLayout") logs += "$name 使用 FrameLayout 近似预览" }
            "ScrollView", "NestedScrollView" -> ScrollView(context)
            "HorizontalScrollView" -> HorizontalScrollView(context)
            "TextView" -> TextView(context)
            "EditText", "TextInputEditText" -> EditText(context)
            "Button", "MaterialButton" -> Button(context)
            "ImageView", "ShapeableImageView" -> ImageView(context).apply { setImageResource(android.R.drawable.ic_menu_gallery); scaleType = ImageView.ScaleType.CENTER_INSIDE }
            "CheckBox" -> CheckBox(context)
            "RadioButton" -> RadioButton(context)
            "Switch", "SwitchMaterial" -> Switch(context)
            "ProgressBar" -> ProgressBar(context)
            "Space", "View" -> View(context)
            else -> TextView(context).apply { text = "<$name>"; setTextColor(Color.parseColor("#7D899B")); setBackgroundColor(Color.parseColor("#EEF2F7")); gravity = Gravity.CENTER }.also { logs += "不支持 $name，已显示占位控件" }
        }
        applyCommon(view, element)
        if (view is TextView) applyText(view, element)
        val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
        if (view is ViewGroup) children.forEach { child ->
            val childView = build(child)
            view.addView(childView, params(view, child))
        }
        return view
    }

    private fun applyCommon(view: View, element: Element) {
        view.visibility = when (attr(element, "visibility")) { "gone" -> View.GONE; "invisible" -> View.INVISIBLE; else -> View.VISIBLE }
        view.alpha = attr(element, "alpha").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        val padding = dim(attr(element, "padding"), 0)
        view.setPadding(
            dim(attr(element, "paddingStart"), padding),
            dim(attr(element, "paddingTop"), padding),
            dim(attr(element, "paddingEnd"), padding),
            dim(attr(element, "paddingBottom"), padding)
        )
        color(attr(element, "background"))?.let { value ->
            view.background = GradientDrawable().apply { setColor(value); cornerRadius = dim(attr(element, "radius"), 0).toFloat() }
        } ?: attr(element, "background").takeIf { it.startsWith("@") }?.let { logs += "未编译资源 $it 使用透明背景" }
    }

    private fun applyText(view: TextView, element: Element) {
        view.text = resourceText(attr(element, "text"))
        view.hint = resourceText(attr(element, "hint"))
        view.textSize = attr(element, "textSize").removeSuffix("sp").toFloatOrNull() ?: 14f
        view.maxLines = attr(element, "maxLines").toIntOrNull() ?: Int.MAX_VALUE
        color(attr(element, "textColor"))?.let(view::setTextColor)
        if (attr(element, "textStyle").contains("bold")) view.setTypeface(view.typeface, Typeface.BOLD)
        view.gravity = gravity(attr(element, "gravity"))
    }

    private fun params(parent: ViewGroup, element: Element): ViewGroup.LayoutParams {
        val width = size(attr(element, "layout_width"))
        val height = size(attr(element, "layout_height"))
        val result = if (parent is LinearLayout) LinearLayout.LayoutParams(width, height, attr(element, "layout_weight").toFloatOrNull() ?: 0f) else FrameLayout.LayoutParams(width, height)
        (result as? ViewGroup.MarginLayoutParams)?.apply {
            val all = dim(attr(element, "layout_margin"), 0)
            setMargins(dim(attr(element, "layout_marginStart"), all), dim(attr(element, "layout_marginTop"), all), dim(attr(element, "layout_marginEnd"), all), dim(attr(element, "layout_marginBottom"), all))
        }
        if (result is FrameLayout.LayoutParams) result.gravity = gravity(attr(element, "layout_gravity"))
        if (result is LinearLayout.LayoutParams) result.gravity = gravity(attr(element, "layout_gravity"))
        return result
    }

    private fun attr(element: Element, name: String): String = element.getAttributeNS(ANDROID_NS, name).ifBlank { element.getAttribute("android:$name") }.trim()
    private fun size(value: String): Int = when (value) { "match_parent", "fill_parent" -> ViewGroup.LayoutParams.MATCH_PARENT; "wrap_content", "" -> ViewGroup.LayoutParams.WRAP_CONTENT; else -> dim(value, ViewGroup.LayoutParams.WRAP_CONTENT) }
    private fun dim(value: String, fallback: Int): Int = value.removeSuffix("dp").removeSuffix("dip").toFloatOrNull()?.let { (it * density).toInt() } ?: fallback
    private fun color(value: String): Int? = runCatching { when { value.startsWith("#") -> Color.parseColor(value); value == "@android:color/transparent" -> Color.TRANSPARENT; value == "@android:color/white" -> Color.WHITE; value == "@android:color/black" -> Color.BLACK; else -> null } }.getOrNull()
    private fun resourceText(value: String): String = when { value.startsWith("@string/") -> value.substringAfter('/').replace('_', ' '); value.startsWith("@") -> "[${value.substringAfter('/')}]"; else -> value }
    private fun gravity(value: String): Int {
        if (value.isBlank()) return Gravity.NO_GRAVITY
        var result = 0
        value.split('|').forEach { result = result or when (it) { "center" -> Gravity.CENTER; "center_horizontal" -> Gravity.CENTER_HORIZONTAL; "center_vertical" -> Gravity.CENTER_VERTICAL; "end", "right" -> Gravity.END; "start", "left" -> Gravity.START; "bottom" -> Gravity.BOTTOM; "top" -> Gravity.TOP; else -> 0 } }
        return result
    }

    companion object { private const val ANDROID_NS = "http://schemas.android.com/apk/res/android" }
}
