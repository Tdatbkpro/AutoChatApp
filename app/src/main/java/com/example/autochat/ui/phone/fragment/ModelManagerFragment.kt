package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.autochat.R
import com.example.autochat.databinding.FragmentModelManagerBinding
import com.example.autochat.llm.ModelManager
import com.example.autochat.ui.phone.adapter.ModelPagerAdapter
import com.example.autochat.ui.phone.viewmodel.ModelViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ModelManagerFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentModelManagerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ModelViewModel by activityViewModels()

    @Inject
    lateinit var modelManager: ModelManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModelManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBottomSheetBehavior()
        setupViewPager()
        setupTabListeners()

        viewModel.loadModels()
    }

    private fun setupBottomSheetBehavior() {
        view?.post {
            val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
            val bottomSheet = dialog?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@post

            bottomSheet.setBackgroundResource(R.drawable.bg_bottom_sheet)

            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
            behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.isHideable = true
            behavior.skipCollapsed = true
        }
    }

    private fun setupViewPager() {
        val pagerAdapter = ModelPagerAdapter(requireActivity())
        binding.viewPager.adapter = pagerAdapter

        // Giảm sensitivity vuốt ngang để không conflict với vuốt đóng bottom sheet
        binding.viewPager.isUserInputEnabled = true

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTabSelection(position)
            }
        })

        // Mặc định tab Model
        updateTabSelection(0)
    }

    private fun setupTabListeners() {
        binding.tabModel.setOnClickListener {
            binding.viewPager.currentItem = 0
        }

        binding.tabToken.setOnClickListener {
            binding.viewPager.currentItem = 1
        }
    }

    private fun updateTabSelection(position: Int) {
        if (position == 0) {
            // Tab Model active
            binding.tabModel.setTextColor(0xFFFFFFFF.toInt())
            binding.tabModel.setBackgroundResource(R.drawable.bg_tab_selected)
            binding.tabModel.setTypeface(null, android.graphics.Typeface.BOLD)

            binding.tabToken.setTextColor(0xFF666677.toInt())
            binding.tabToken.setBackgroundResource(R.drawable.bg_tab_unselected)
            binding.tabToken.setTypeface(null, android.graphics.Typeface.NORMAL)
        } else {
            // Tab Token active
            binding.tabToken.setTextColor(0xFFFFFFFF.toInt())
            binding.tabToken.setBackgroundResource(R.drawable.bg_tab_selected)
            binding.tabToken.setTypeface(null, android.graphics.Typeface.BOLD)

            binding.tabModel.setTextColor(0xFF666677.toInt())
            binding.tabModel.setBackgroundResource(R.drawable.bg_tab_unselected)
            binding.tabModel.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}