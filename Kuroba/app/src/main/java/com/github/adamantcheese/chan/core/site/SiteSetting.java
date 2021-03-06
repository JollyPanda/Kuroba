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
package com.github.adamantcheese.chan.core.site;

import com.github.adamantcheese.chan.core.settings.primitives.Setting;

import java.util.List;

import static com.github.adamantcheese.chan.core.site.SiteSetting.Type.OPTIONS;
import static com.github.adamantcheese.chan.core.site.SiteSetting.Type.STRING;

/**
 * Hacky stuff to give the site settings a good UI.
 */
public class SiteSetting<T> {
    public enum Type {
        OPTIONS,
        STRING
    }

    public final String name;
    public final Type type;
    public final Setting<T> setting;
    public List<String> optionNames;

    public SiteSetting(String name, Setting<T> setting, List<String> optionNames) {
        this.name = name;
        this.type = optionNames == null ? STRING : OPTIONS;
        this.setting = setting;
        this.optionNames = optionNames;
    }
}
