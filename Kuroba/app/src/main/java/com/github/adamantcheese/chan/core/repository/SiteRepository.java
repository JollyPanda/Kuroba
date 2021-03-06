package com.github.adamantcheese.chan.core.repository;

import android.text.TextUtils;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;

import com.github.adamantcheese.chan.core.database.DatabaseManager;
import com.github.adamantcheese.chan.core.model.json.site.SiteConfig;
import com.github.adamantcheese.chan.core.model.orm.Filter;
import com.github.adamantcheese.chan.core.model.orm.Loadable;
import com.github.adamantcheese.chan.core.model.orm.SiteModel;
import com.github.adamantcheese.chan.core.settings.primitives.JsonSettings;
import com.github.adamantcheese.chan.core.site.Site;
import com.github.adamantcheese.chan.utils.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Observable;

import javax.inject.Inject;

import static com.github.adamantcheese.chan.core.site.SiteRegistry.SITE_CLASSES;

public class SiteRepository {
    private DatabaseManager databaseManager;
    private Sites sitesObservable = new Sites();

    public Site forId(int id) {
        return sitesObservable.forId(id);
    }

    @Inject
    public SiteRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Sites all() {
        return sitesObservable;
    }

    public SiteModel byId(int id) {
        return databaseManager.runTask(databaseManager.getDatabaseSiteManager().byId(id));
    }

    public void updateUserSettings(Site site, JsonSettings jsonSettings) {
        SiteModel siteModel = byId(site.id());
        if (siteModel == null) throw new NullPointerException("siteModel == null");
        updateSiteUserSettingsAsync(siteModel, jsonSettings);
    }

    public void updateSiteUserSettingsAsync(SiteModel siteModel, JsonSettings jsonSettings) {
        siteModel.storeUserSettings(jsonSettings);
        databaseManager.runTaskAsync(databaseManager.getDatabaseSiteManager().update(siteModel));
    }

    public Map<Integer, Integer> getOrdering() {
        return databaseManager.runTask(databaseManager.getDatabaseSiteManager().getOrdering());
    }

    public void updateSiteOrderingAsync(List<Site> sites) {
        List<Integer> ids = new ArrayList<>(sites.size());
        for (Site site : sites) {
            ids.add(site.id());
        }

        databaseManager.runTaskAsync(databaseManager.getDatabaseSiteManager().updateOrdering(ids), r -> {
            sitesObservable.wasReordered();
            sitesObservable.notifyObservers();
        });
    }

    public void initialize() {
        List<Site> sites = new ArrayList<>();

        List<SiteModel> models = databaseManager.runTask(databaseManager.getDatabaseSiteManager().getAll());

        for (SiteModel siteModel : models) {
            SiteConfigSettingsHolder holder;
            try {
                holder = instantiateSiteFromModel(siteModel);
            } catch (IllegalArgumentException e) {
                Logger.e(this, "instantiateSiteFromModel", e);
                break;
            }

            Site site = holder.site;
            SiteConfig config = holder.config;
            JsonSettings settings = holder.settings;

            site.initialize(siteModel.id, config, settings);

            sites.add(site);
        }

        sitesObservable.addAll(sites);

        for (Site site : sites) {
            site.postInitialize();
        }

        sitesObservable.notifyObservers();
    }

    public Site createFromClass(Class<? extends Site> siteClass) {
        Site site = instantiateSiteClass(siteClass);

        SiteConfig config = new SiteConfig();
        JsonSettings settings = new JsonSettings();

        //the index doesn't necessarily match the key value to get the class ID anymore since sites were removed
        config.classId = SITE_CLASSES.keyAt(SITE_CLASSES.indexOfValue(site.getClass()));
        config.external = false;

        SiteModel model = createFromClass(config, settings);

        site.initialize(model.id, config, settings);

        sitesObservable.add(site);

        site.postInitialize();

        sitesObservable.notifyObservers();

        return site;
    }

    private SiteModel createFromClass(SiteConfig config, JsonSettings userSettings) {
        SiteModel siteModel = new SiteModel();
        siteModel.storeConfig(config);
        siteModel.storeUserSettings(userSettings);
        databaseManager.runTask(databaseManager.getDatabaseSiteManager().add(siteModel));

        return siteModel;
    }

    private SiteConfigSettingsHolder instantiateSiteFromModel(SiteModel siteModel) {
        Pair<SiteConfig, JsonSettings> configFields = siteModel.loadConfigFields();
        SiteConfig config = configFields.first;
        JsonSettings settings = configFields.second;

        return new SiteConfigSettingsHolder(instantiateSiteClass(config.classId), config, settings);
    }

    @NonNull
    private Site instantiateSiteClass(int classId) {
        Class<? extends Site> clazz = SITE_CLASSES.get(classId);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown class id: " + classId);
        }
        return instantiateSiteClass(clazz);
    }

    @NonNull
    public Site instantiateSiteClass(Class<? extends Site> clazz) {
        try {
            return clazz.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalArgumentException();
        }
    }

    public void removeSite(Site site) {
        databaseManager.runTask(() -> {
            removeFilters(site);
            databaseManager.getDatabaseBoardManager().deleteBoards(site).call();

            List<Loadable> siteLoadables = databaseManager.getDatabaseLoadableManager().getLoadables(site).call();
            if (!siteLoadables.isEmpty()) {
                databaseManager.getDatabaseSavedThreadManager().deleteSavedThreads(siteLoadables).call();
                databaseManager.getDatabasePinManager().deletePinsFromLoadables(siteLoadables).call();
                databaseManager.getDatabaseHistoryManager().deleteHistory(siteLoadables).call();
                databaseManager.getDatabaseLoadableManager().deleteLoadables(siteLoadables).call();
            }

            databaseManager.getDatabaseSavedReplyManager().deleteSavedReplies(site).call();
            databaseManager.getDatabaseHideManager().deleteThreadHides(site).call();
            databaseManager.getDatabaseSiteManager().deleteSite(site).call();
            return null;
        });
    }

    private void removeFilters(Site site)
            throws Exception {
        List<Filter> filtersToDelete = new ArrayList<>();

        for (Filter filter : databaseManager.getDatabaseFilterManager().getFilters().call()) {
            if (filter.allBoards || TextUtils.isEmpty(filter.boards)) {
                continue;
            }

            for (String uniqueId : filter.boards.split(",")) {
                String[] split = uniqueId.split(":");
                if (split.length == 2 && Integer.parseInt(split[0]) == site.id()) {
                    filtersToDelete.add(filter);
                    break;
                }
            }
        }

        databaseManager.getDatabaseFilterManager().deleteFilters(filtersToDelete).call();
    }

    public class Sites
            extends Observable {
        private List<Site> sites = Collections.unmodifiableList(new ArrayList<>());
        private SparseArray<Site> sitesById = new SparseArray<>();

        public Site forId(int id) {
            return sitesById.get(id);
        }

        public List<Site> getAll() {
            return new ArrayList<>(sites);
        }

        public List<Site> getAllInOrder() {
            Map<Integer, Integer> ordering = getOrdering();

            List<Site> ordered = new ArrayList<>(sites);
            //noinspection ConstantConditions
            Collections.sort(
                    ordered,
                    (lhs, rhs) -> lhs == null || rhs == null ? 0 : ordering.get(lhs.id()) - ordering.get(rhs.id())
            );

            return ordered;
        }

        private void addAll(@NonNull List<Site> all) {
            List<Site> copy = new ArrayList<>(sites);
            copy.addAll(all);
            resetSites(copy);
            setChanged();
        }

        private void add(@NonNull Site site) {
            List<Site> copy = new ArrayList<>(sites);
            copy.add(site);
            resetSites(copy);
            setChanged();
        }

        // We don't keep the order ourselves here, that's the task of listeners. Do notify the
        // listeners.
        private void wasReordered() {
            setChanged();
        }

        private void resetSites(@NonNull List<Site> newSites) {
            sites = Collections.unmodifiableList(newSites);
            SparseArray<Site> byId = new SparseArray<>(newSites.size());
            for (Site newSite : newSites) {
                byId.put(newSite.id(), newSite);
            }
            sitesById = byId;
        }
    }

    private static class SiteConfigSettingsHolder {
        @NonNull
        Site site;
        @NonNull
        SiteConfig config;
        @NonNull
        JsonSettings settings;

        public SiteConfigSettingsHolder(
                @NonNull Site site, @NonNull SiteConfig config, @NonNull JsonSettings settings
        ) {
            this.site = site;
            this.config = config;
            this.settings = settings;
        }
    }
}
