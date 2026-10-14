package com.tvhub.media.core

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** Clear, cheap focus feedback for remote navigation. */
object Focus {
    private const val SCALE = 1.06f

    fun attach(view: View) {
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus -> apply(v, hasFocus) }
    }

    fun apply(v: View, hasFocus: Boolean) {
        val s = if (hasFocus) SCALE else 1f
        v.animate().cancel()
        if (Prefs.animations) {
            v.animate().scaleX(s).scaleY(s).setDuration(110).start()
        } else {
            v.scaleX = s
            v.scaleY = s
        }
        v.translationZ = if (hasFocus) 8f else 0f
    }

    /** Called from onBind so recycled views never keep a stale "focused" scale. */
    fun reset(v: View) {
        val focused = v.isFocused
        v.animate().cancel()
        v.scaleX = if (focused) SCALE else 1f
        v.scaleY = if (focused) SCALE else 1f
        v.translationZ = if (focused) 8f else 0f
    }
}

/** Puts focus on [position], scrolling to it first if needed. Never leaves focus nowhere. */
fun RecyclerView.focusPosition(position: Int) {
    val count = adapter?.itemCount ?: 0
    if (count == 0) {
        requestFocus()
        return
    }
    val pos = position.coerceIn(0, count - 1)
    findViewHolderForAdapterPosition(pos)?.let {
        it.itemView.requestFocus()
        return
    }
    scrollToPosition(pos)
    post {
        val vh = findViewHolderForAdapterPosition(pos)
        if (vh != null) vh.itemView.requestFocus() else requestFocus()
    }
}

/** Adapter position of the child that currently holds focus, or -1. */
fun RecyclerView.focusedPosition(): Int {
    val f = focusedChild ?: return -1
    return getChildAdapterPosition(f)
}
