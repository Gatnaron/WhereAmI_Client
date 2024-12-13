package com.example.joymap.Services

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.BaseAdapter
import com.example.joymap.R

class ChildrenAdapter(
    private val context: Context,
    private val children: List<String>
) : BaseAdapter() {

    override fun getCount(): Int = children.size

    override fun getItem(position: Int): String = children[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.child_item, parent, false)
        val childDeviceId = view.findViewById<TextView>(R.id.childDeviceId)
        childDeviceId.text = children[position]
        return view
    }
}