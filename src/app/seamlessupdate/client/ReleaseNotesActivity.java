// SPDX-License-Identifier: Apache-2.0
package app.seamlessupdate.client;

import android.os.Bundle;
import android.os.UserManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.LeadingMarginSpan;
import android.util.TypedValue;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/**
 * Release notes of one build, from the phone's own store. The header is the app's own, the notes
 * are plain text: no links, no markup and no selection, as they come from the servers unsigned.
 */
public class ReleaseNotesActivity extends CollapsingToolbarBaseActivity {
    static final String EXTRA_KEY = "key";
    static final String EXTRA_BUILD = "build";
    static final String EXTRA_TARGET = "target";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!getSystemService(UserManager.class).isSystemUser()) {
            throw new SecurityException("system user only");
        }
        final String key = getIntent().getStringExtra(EXTRA_KEY);
        final String build = getIntent().getStringExtra(EXTRA_BUILD);
        final String notes = NotesStore.read(this, key);
        if (notes == null || build == null || !build.matches("[0-9]{1,18}")
                || !key.startsWith(build + "-")) {
            finish();
            return;
        }
        setTitle(getString(getIntent().getBooleanExtra(EXTRA_TARGET, false)
                ? R.string.whats_new_title : R.string.release_notes_title_build, build));

        final TextView text = new TextView(this);
        text.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        text.setTextIsSelectable(false);
        text.setLinksClickable(false);
        final int pad = px(24);
        text.setPadding(pad, px(8), pad, pad);
        text.setText(render(notes));

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }

    private int px(final int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }

    // Two bullet levels from the notes' own line grammar, so a long bullet wraps under its text.
    private CharSequence render(final String notes) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        final int gap = px(8);
        final int indent = px(16);
        boolean first = true;
        for (final ReleaseNotes.Line line : ReleaseNotes.lines(notes)) {
            if (!first) out.append(line.level == 0 ? "\n\n" : "\n");
            first = false;
            final int start = out.length();
            out.append(line.text);
            final int end = out.length();
            if (line.level == 2) {
                out.setSpan(new LeadingMarginSpan.Standard(indent), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (line.level > 0) {
                out.setSpan(new BulletSpan(gap), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return out;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
