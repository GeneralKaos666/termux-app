package com.termux.app.fragments.settings

import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.annotation.Keep
import androidx.appcompat.widget.SearchView
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import com.termux.R
import com.termux.app.domain.settings.SettingsEntry
import com.termux.app.domain.settings.filterSettings
import com.termux.app.settings.settingsIndex
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences

@Keep
class TermuxPreferencesFragment : PreferenceFragmentCompat() {

    /** Every row the search can offer, captured once from the inflated screen. */
    private var mAllEntries: List<SettingsEntry> = emptyList()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = this.context ?: return

        val preferenceManager: PreferenceManager = preferenceManager
        preferenceManager.setPreferenceDataStore(TermuxPreferencesDataStore.getInstance(context))

        setPreferencesFromResource(R.xml.termux_preferences, rootKey)

        setHasOptionsMenu(true)
        mAllEntries = settingsIndex(preferenceScreen ?: return)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // The search view is rebuilt with the view and starts collapsed and empty, so a filtered
        // screen must not survive a return from a result row; show the real screen again.
        setPreferencesFromResource(R.xml.termux_preferences, null)
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        val context = this.context ?: return

        val searchView = SearchView(context)
        searchView.queryHint = getString(R.string.settings_search_hint)
        searchView.setIconifiedByDefault(false)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                return true
            }

            override fun onQueryTextChange(query: String): Boolean {
                showMatches(query)
                return true
            }
        })

        val item = menu.add(
            Menu.NONE, R.id.settings_search, Menu.NONE,
            R.string.settings_search_hint,
        )
        item.actionView = searchView
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
    }

    /**
     * Replaces the screen with the rows matching [query].
     *
     * A blank query restores the real screen rather than a rebuilt copy: the resource is the one
     * source of the list, and re-inflating it cannot drift from it.
     *
     * Matches are rendered into a fresh screen and swapped in whole. Mutating the attached screen
     * (`removeAll()` plus `addPreference()`) while its list is laid out leaves the preference list
     * bound to a stale hierarchy; opening a row from that rebuilt screen then crashed with
     * `PreferenceFragmentCompat.bindPreferences` on a null list.
     */
    private fun showMatches(query: String) {
        // Collapsing the search view fires one last query change, and that can arrive after the
        // fragment's view was replaced by the row that was just tapped. Rebuilding then posts a
        // bind to a destroyed list view (PreferenceFragmentCompat.bindPreferences NPE).
        if (view == null) return

        if (query.trim().isEmpty()) {
            setPreferencesFromResource(R.xml.termux_preferences, null)
            return
        }

        val matches = filterSettings(mAllEntries, query)
        val screen: PreferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

        if (matches.isEmpty()) {
            val empty = Preference(requireContext())
            empty.setTitle(R.string.settings_search_no_results)
            empty.isSelectable = false
            screen.addPreference(empty)
        } else {
            for (entry in matches) {
                val row = Preference(requireContext())
                row.key = entry.key
                row.title = entry.title
                row.summary = entry.summary
                row.fragment = entry.fragment
                screen.addPreference(row)
            }
        }

        setPreferenceScreen(screen)
    }

}

class TermuxPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val mContext: Context = context
    private val mPreferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context, true)

    companion object {
        private var mInstance: TermuxPreferencesDataStore? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): TermuxPreferencesDataStore {
            if (mInstance == null) {
                mInstance = TermuxPreferencesDataStore(context)
            }
            return mInstance!!
        }
    }

}
