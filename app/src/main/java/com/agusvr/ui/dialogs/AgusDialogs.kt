package com.agusvr.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.agusvr.R
import com.agusvr.ui.widgets.AgusWidgets
import com.agusvr.util.Ui
import com.agusvr.util.dp

/**
 * On-identity dialogs (module: AgusSpatialUI). Nothing here uses the stock
 * Android alert look: dark glass cards, Orbitron titles, Agus buttons.
 */
object AgusDialogs {

    private fun baseDialog(context: Context, title: String): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(context, R.style.Theme_Agus_Dialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_dialog)
            setPadding(context.dp(22), context.dp(18), context.dp(22), context.dp(18))
        }
        root.addView(AgusWidgets.panelTitle(context, title).apply {
            setPadding(0, 0, 0, context.dp(10))
        })
        dialog.setContentView(root, ViewGroup.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.72f).toInt().coerceAtMost(context.dp(430)),
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dialog.window?.setGravity(Gravity.CENTER)
        return dialog to root
    }

    fun info(context: Context, title: String, body: String, onOk: (() -> Unit)? = null): Dialog {
        val (dialog, root) = baseDialog(context, title)
        root.addView(AgusWidgets.bodyText(context, body))
        val btn = AgusWidgets.primaryButton(context, "OK") {
            dialog.dismiss(); onOk?.invoke()
        }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(16); gravity = Gravity.END
        })
        dialog.show()
        return dialog
    }

    fun confirm(
        context: Context, title: String, body: String,
        confirmLabel: String = "Confirmar", danger: Boolean = false,
        onConfirm: () -> Unit
    ): Dialog {
        val (dialog, root) = baseDialog(context, title)
        root.addView(AgusWidgets.bodyText(context, body))
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(AgusWidgets.ghostButton(context, "Cancelar") { dialog.dismiss() })
        val ok = AgusWidgets.primaryButton(context, confirmLabel) {
            dialog.dismiss(); onConfirm()
        }
        if (danger) ok.setTextColor(Color.parseColor("#2A0510"))
        buttons.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(10)
        })
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(16)
        })
        dialog.show()
        return dialog
    }

    fun input(
        context: Context, title: String, hint: String, initial: String = "",
        okLabel: String = "OK", onSubmit: (String) -> Unit
    ): Pair<Dialog, EditText> {
        val (dialog, root) = baseDialog(context, title)
        val field = AgusWidgets.input(context, hint, initial)
        root.addView(field)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(AgusWidgets.ghostButton(context, "Cancelar") { dialog.dismiss() })
        buttons.addView(AgusWidgets.primaryButton(context, okLabel) {
            val v = field.text.toString().trim()
            if (v.isNotEmpty()) {
                dialog.dismiss()
                onSubmit(v)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(10)
        })
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(14)
        })
        dialog.show()
        return dialog to field
    }

    /** Bottom-sheet-like list of actions. */
    fun actionSheet(context: Context, title: String, actions: List<Pair<String, () -> Unit>>): Dialog {
        val (dialog, root) = baseDialog(context, title)
        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for ((label, action) in actions) {
            val row = TextView(context).apply {
                text = label
                setTextColor(AgusWidgets.TEXT)
                textSize = 12.5f
                typeface = Ui.ui
                setPadding(context.dp(14), context.dp(13), context.dp(14), context.dp(13))
                background = context.getDrawable(R.drawable.bg_card)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Ui.tick(this); Ui.blip()
                    dialog.dismiss()
                    action()
                }
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(7)
            })
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        return dialog
    }

    fun custom(context: Context, title: String): Pair<Dialog, LinearLayout> {
        val pair = baseDialog(context, title)
        pair.first.show()
        return pair
    }

    /** Makes a view dismiss the dialog on tap outside-ish usage. */
    fun dismissOnClick(dialog: Dialog, v: View) {
        v.setOnClickListener { dialog.dismiss() }
    }
}
