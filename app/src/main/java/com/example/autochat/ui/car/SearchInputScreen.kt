package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import com.example.autochat.ui.car.MyChatScreen

class SearchInputScreen(
    carContext: CarContext,
    private val chatScreen: MyChatScreen
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) {}

                override fun onSearchSubmitted(searchText: String) {
                    if (searchText.isNotBlank()) {
//                        chatScreen.addUserMessage(searchText)
                    }
                    screenManager.pop()
//                    screenManager.pop()
                }
            }
        )
            .setInitialSearchText("")
            .setSearchHint("Nhập câu hỏi...")
            .setHeaderAction(Action.BACK)
            .build()
    }
}