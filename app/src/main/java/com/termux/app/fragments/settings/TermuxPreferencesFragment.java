package com.termux.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.annotation.Keep;
import androidx.appcompat.widget.SearchView;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.termux.R;
import com.termux.app.domain.settings.SettingsEntry;
import com.termux.app.settings.SettingsIndexBuilderKt;
import com.termux.app.domain.settings.SettingsIndexKt;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.util.Collections;
import java.util.List;

@Keep
public class TermuxPreferencesFragment extends PreferenceFragmentCompat {

    /** Every row the search can offer, captured once from the inflated screen. */
    private List<SettingsEntry> mAllEntries = Collections.emptyList();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(TermuxPreferencesDataStore.getInstance(context));

        setPreferencesFromResource(R.xml.termux_preferences, rootKey);

        setHasOptionsMenu(true);
        mAllEntries = SettingsIndexBuilderKt.settingsIndex(getPreferenceScreen());
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        Context context = getContext();
        if (context == null) return;

        SearchView searchView = new SearchView(context);
        searchView.setQueryHint(getString(R.string.settings_search_hint));
        searchView.setIconifiedByDefault(false);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String query) {
                showMatches(query);
                return true;
            }
        });

        MenuItem item = menu.add(Menu.NONE, R.id.settings_search, Menu.NONE,
            R.string.settings_search_hint);
        item.setActionView(searchView);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
    }

    /**
     * Replaces the screen with the rows matching [query].
     *
     * A blank query restores the real screen rather than a rebuilt copy: the resource is the one
     * source of the list, and re-inflating it cannot drift from it.
     */
    private void showMatches(String query) {
        if (query.trim().isEmpty()) {
            setPreferencesFromResource(R.xml.termux_preferences, null);
            return;
        }

        List<SettingsEntry> matches = SettingsIndexKt.filterSettings(mAllEntries, query);
        PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();

        if (matches.isEmpty()) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.settings_search_no_results);
            empty.setSelectable(false);
            screen.addPreference(empty);
            return;
        }

        for (SettingsEntry entry : matches) {
            Preference row = new Preference(requireContext());
            row.setKey(entry.getKey());
            row.setTitle(entry.getTitle());
            row.setSummary(entry.getSummary());
            row.setFragment(entry.getFragment());
            screen.addPreference(row);
        }
    }

}

class TermuxPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TermuxPreferencesDataStore mInstance;

    private TermuxPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TermuxPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TermuxPreferencesDataStore(context);
        }
        return mInstance;
    }

}
