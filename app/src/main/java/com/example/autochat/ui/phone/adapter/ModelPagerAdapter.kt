package com.example.autochat.ui.phone.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.autochat.ui.phone.fragment.HFTokenFragment
import com.example.autochat.ui.phone.fragment.ModelListFragment

class ModelPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ModelListFragment()
            1 -> HFTokenFragment()
            else -> throw IllegalArgumentException("Invalid position")
        }
    }
}