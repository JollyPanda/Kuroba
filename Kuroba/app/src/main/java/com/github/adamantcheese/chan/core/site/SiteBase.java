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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.adamantcheese.chan.core.manager.BoardManager;
import com.github.adamantcheese.chan.core.model.json.site.SiteConfig;
import com.github.adamantcheese.chan.core.model.orm.Board;
import com.github.adamantcheese.chan.core.repository.SiteRepository;
import com.github.adamantcheese.chan.core.settings.provider.SettingProvider;
import com.github.adamantcheese.chan.core.settings.primitives.JsonSettings;
import com.github.adamantcheese.chan.core.settings.provider.JsonSettingsProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.HttpUrl;

import static com.github.adamantcheese.chan.Chan.instance;

public abstract class SiteBase
        implements Site {
    protected int id;
    protected SiteConfig config;

    protected BoardManager boardManager;
    protected SettingProvider<Object> settingsProvider;
    private JsonSettings userSettings;
    private boolean initialized = false;

    @Override
    public void initialize(int id, SiteConfig config, JsonSettings userSettings) {
        this.id = id;
        this.config = config;
        this.userSettings = userSettings;

        if (initialized) {
            throw new IllegalStateException();
        }
        initialized = true;
    }

    @Override
    public void postInitialize() {
        boardManager = instance(BoardManager.class);
        SiteRepository siteRepository = instance(SiteRepository.class);

        settingsProvider =
                new JsonSettingsProvider(userSettings, () -> siteRepository.updateUserSettings(this, userSettings));

        initializeSettings();

        if (boardsType().canList) {
            actions().boards(boards -> boardManager.updateAvailableBoardsForSite(this, boards.boards));
        }
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public Board board(String code) {
        return boardManager.getBoard(this, code);
    }

    @Override
    public List<SiteSetting<?>> settings() {
        return new ArrayList<>();
    }

    public void initializeSettings() {
    }

    @NonNull
    @Override
    public ChunkDownloaderSiteProperties getChunkDownloaderSiteProperties() {
        // by default, assume everything is bad
        return new ChunkDownloaderSiteProperties(false, false);
    }

    @Override
    public Board createBoard(String name, String code) {
        Board existing = board(code);
        if (existing != null) {
            return existing;
        }

        Board board = Board.fromSiteNameCode(this, name, code);
        boardManager.updateAvailableBoardsForSite(this, Collections.singletonList(board));
        return board;
    }

    public static boolean containsMediaHostUrl(HttpUrl desiredSiteUrl, String[] mediaHosts) {
        String host = desiredSiteUrl.host();

        for (String mediaHost : mediaHosts) {
            if (host.equals(mediaHost)) {
                return true;
            }

            if (host.equals("www." + mediaHost)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null) return false;
        // sites are equal if their class names are equal (not classes directly, as those don't have a proper equals implementation)
        if (!(obj.getClass().getSimpleName().equals(this.getClass().getSimpleName()))) return false;
        return ((Site) obj).name().equals(this.name());
    }
}
