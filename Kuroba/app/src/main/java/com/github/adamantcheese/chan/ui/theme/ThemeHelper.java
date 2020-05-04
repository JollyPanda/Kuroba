/*
 * Kuroba - *chan browser https://github.com/Adamantcheese/Kuroba/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.ui.theme;

import android.app.ActivityManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;

import androidx.annotation.AnyThread;
import androidx.appcompat.app.AppCompatActivity;

import com.github.adamantcheese.chan.R;
import com.github.adamantcheese.chan.core.settings.ChanSettings;
import com.github.adamantcheese.chan.utils.Logger;

import java.util.ArrayList;
import java.util.List;

import static com.github.adamantcheese.chan.Chan.instance;
import static com.github.adamantcheese.chan.ui.theme.Theme.MaterialColorStyle.BLACK;
import static com.github.adamantcheese.chan.ui.theme.Theme.MaterialColorStyle.BROWN;
import static com.github.adamantcheese.chan.ui.theme.Theme.MaterialColorStyle.DARK;
import static com.github.adamantcheese.chan.ui.theme.Theme.MaterialColorStyle.GREEN;
import static com.github.adamantcheese.chan.ui.theme.Theme.MaterialColorStyle.ORANGE;
import static com.github.adamantcheese.chan.ui.theme.Theme.MaterialColorStyle.RED;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getAppContext;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getApplicationLabel;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getRes;

public class ThemeHelper {
    /*
        Theme guide, continued from styles.xml
        4) Add in a new line
        themes.add(new Theme("NAME", R.style.NAMEHERE, PRIMARY_COLOR, true if your theme's parent is Light, false if your theme's parent is Dark));
        If you want to change the default fonts for your themes, there's a constructor and example for that.

        5) That's it! Everything else is taken care of for you automatically.
     */
    private List<Theme> themes = new ArrayList<>();

    private Theme theme;
    public static Theme defaultTheme = new Theme("Yotsuba", R.style.Chan_Theme_Yotsuba, RED, true);

    private static final Typeface TALLEYRAND =
            Typeface.createFromAsset(getAppContext().getAssets(), "font/Talleyrand.ttf");
    private static final Typeface OPTI_CUBA_LIBRE_TWO =
            Typeface.createFromAsset(getAppContext().getAssets(), "font/OPTICubaLibreTwo.otf");

    public ThemeHelper() {
        themes.add(new Theme("Light", R.style.Chan_Theme_Light, GREEN, true));
        themes.add(new Theme("Dark", R.style.Chan_Theme_Dark, DARK, false));
        themes.add(new Theme("Black", R.style.Chan_Theme_Black, BLACK, false));
        themes.add(new Theme("Tomorrow", R.style.Chan_Theme_Tomorrow, DARK, false));
        themes.add(new Theme("Tomorrow Black", R.style.Chan_Theme_TomorrowBlack, BLACK, false));
        themes.add(defaultTheme);
        themes.add(new Theme("Yotsuba B", R.style.Chan_Theme_YotsubaB, RED, true));
        themes.add(new Theme("Photon", R.style.Chan_Theme_Photon, ORANGE, true));
        themes.add(new Theme("Insomnia", R.style.Chan_Theme_Insomnia, DARK, false));
        themes.add(new Theme("Gruvbox", R.style.Chan_Theme_Gruvbox, DARK, false));
        themes.add(new Theme("Neon", R.style.Chan_Theme_Neon, DARK, false));
        themes.add(new Theme("Solarized Dark", R.style.Chan_Theme_SolarizedDark, ORANGE, false));
        Theme holo = new Theme("Holo", R.style.Chan_Theme_Holo, BROWN, TALLEYRAND, OPTI_CUBA_LIBRE_TWO, false);
        holo.altFontIsMain = true;
        themes.add(holo);

        String[] split = ChanSettings.theme.get().split(",");
        for (Theme theme : themes) {
            if (theme.name.equals(split[0])) {
                try {
                    this.theme = theme;
                    this.theme.primaryColor = Theme.MaterialColorStyle.valueOf(split[1]);
                    this.theme.accentColor = Theme.MaterialColorStyle.valueOf(split[2]);
                    return;
                } catch (Exception ignored) {
                    // theme name matches, but something else is wrong with the setting
                    break;
                }
            }
        }

        Logger.e(this, "No theme found for setting, using default theme");
        ChanSettings.theme.set(defaultTheme.toString());
        theme = defaultTheme;
    }

    @AnyThread
    public static Theme getTheme() {
        return instance(ThemeHelper.class).theme;
    }

    public static void resetThemes() {
        for (Theme theme : getThemes()) {
            theme.reset();
        }
    }

    public static List<Theme> getThemes() {
        return instance(ThemeHelper.class).themes;
    }

    public static void setupContext(AppCompatActivity context) {
        //set the theme to the newly made theme and setup some small extras
        context.getTheme().setTo(createTheme(getTheme()));
        Bitmap taskDescriptionBitmap = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher);
        context.setTaskDescription(new ActivityManager.TaskDescription(getApplicationLabel(), taskDescriptionBitmap));
    }

    public static Resources.Theme createTheme(Theme theme) {
        //create the proper Android theme instance from the local theme, and the selected colors
        Resources.Theme userTheme = getRes().newTheme();
        userTheme.applyStyle(theme.resValue, true); // main styling theme first
        userTheme.applyStyle(theme.primaryColor.primaryColorStyleId, true); // toolbar color, status bar color
        userTheme.applyStyle(theme.accentColor.accentStyleId, true); // FAB, ui element color
        return userTheme;
    }
}
