package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.autochat.R
import com.example.autochat.databinding.FragmentHistoryBinding
import com.example.autochat.ui.phone.adapter.SessionAdapter
import com.example.autochat.ui.phone.viewmodel.HistoryViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var adapter: SessionAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SessionAdapter(
            onSessionClick = { session ->
                findNavController().navigate(
                    R.id.action_history_to_detail,
                    Bundle().apply {
                        putString("sessionId", session.id)
                        putString("sessionTitle", session.title)
                    }
                )
            },
            onDeleteClick = { session ->
                viewModel.deleteSession(session.id)
            }
        )

        binding.recyclerSessions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sessions.collect { sessions ->
                adapter.submitList(sessions)
                binding.tvEmpty.visibility =
                    if (sessions.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}