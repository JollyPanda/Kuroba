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
package com.github.adamantcheese.chan.ui.adapter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.vectordrawable.graphics.drawable.Animatable2Compat;
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat;

import com.github.adamantcheese.chan.R;
import com.github.adamantcheese.chan.core.manager.SettingsNotificationManager;
import com.github.adamantcheese.chan.core.manager.WatchManager;
import com.github.adamantcheese.chan.core.model.orm.Board;
import com.github.adamantcheese.chan.core.model.orm.Pin;
import com.github.adamantcheese.chan.core.model.orm.PinType;
import com.github.adamantcheese.chan.core.model.orm.SavedThread;
import com.github.adamantcheese.chan.core.settings.ChanSettings;
import com.github.adamantcheese.chan.ui.helper.PinHelper;
import com.github.adamantcheese.chan.ui.helper.PostHelper;
import com.github.adamantcheese.chan.ui.settings.SettingNotificationType;
import com.github.adamantcheese.chan.ui.theme.ThemeHelper;
import com.github.adamantcheese.chan.ui.view.ThumbnailView;
import com.github.adamantcheese.chan.utils.AnimationUtils;
import com.github.adamantcheese.chan.utils.BackgroundUtils;
import com.github.adamantcheese.chan.utils.Logger;
import com.github.adamantcheese.chan.utils.StringUtils;

import javax.inject.Inject;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.github.adamantcheese.chan.Chan.inject;
import static com.github.adamantcheese.chan.utils.AndroidUtils.dp;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getAttrColor;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getAttrDrawable;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getColor;
import static com.github.adamantcheese.chan.utils.AndroidUtils.getRes;
import static com.github.adamantcheese.chan.utils.AndroidUtils.sp;
import static com.github.adamantcheese.chan.utils.AndroidUtils.updatePaddings;
import static com.github.adamantcheese.chan.utils.LayoutUtils.inflate;

public class DrawerAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    /**
     * PIN_OFFSET is the number of items before the pins
     * (in this case, settings, history, and the bookmarked threads title)
     * see {@link #getItemViewType(int) if you change this value}
     */
    private static final int PIN_OFFSET = 3;
    private static final int SETTINGS_OFFSET = 0;

    //NOTE: TYPE_PIN is public so that in DrawerController we can set it's recycled count to 0 in the pool
    //We avoid recycling them as it was causing issues with the TextViews internal to those holders which update their text style
    private static final int TYPE_HEADER = 0;
    public static final int TYPE_PIN = 1;
    private static final int TYPE_LINK = 2;

    @Inject
    WatchManager watchManager;
    @Inject
    SettingsNotificationManager settingsNotificationManager;

    private Context context;
    private Drawable downloadIconOutline;
    private Drawable downloadIconFilled;

    private final Callback callback;
    private Pin highlighted;
    private Bitmap archivedIcon;
    private Bitmap stickyIcon;

    public DrawerAdapter(Callback callback, Context context) {
        inject(this);
        this.callback = callback;
        this.context = context;
        setHasStableIds(true);

        downloadIconOutline = context.getDrawable(R.drawable.ic_download_anim0).mutate();
        downloadIconOutline.setTint(getAttrColor(context, android.R.attr.textColorPrimary));

        downloadIconFilled = context.getDrawable(R.drawable.ic_download_anim1).mutate();
        downloadIconFilled.setTint(Color.GRAY);

        archivedIcon = BitmapFactory.decodeResource(getRes(), R.drawable.archived_icon);
        stickyIcon = BitmapFactory.decodeResource(getRes(), R.drawable.sticky_icon);
    }

    public void setPinHighlighted(Pin highlighted) {
        this.highlighted = highlighted;
    }

    public ItemTouchHelper.Callback getItemTouchHelperCallback() {
        return new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
                boolean pin = getItemViewType(viewHolder.getAdapterPosition()) == TYPE_PIN;
                int dragFlags = pin ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0;
                int swipeFlags = pin ? ItemTouchHelper.RIGHT | ItemTouchHelper.LEFT : 0;

                return makeMovementFlags(dragFlags, swipeFlags);
            }

            @Override
            public boolean onMove(
                    RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target
            ) {
                int from = viewHolder.getAdapterPosition();
                int to = target.getAdapterPosition();

                if (getItemViewType(to) == TYPE_PIN) {
                    Pin item = watchManager.getAllPins().remove(from - PIN_OFFSET);
                    watchManager.getAllPins().add(to - PIN_OFFSET, item);
                    watchManager.reorder();
                    notifyItemMoved(from, to);
                    return true;
                } else {
                    return false;
                }
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                callback.onPinRemoved(watchManager.getAllPins().get(viewHolder.getAdapterPosition() - PIN_OFFSET));
            }
        };
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        switch (viewType) {
            case TYPE_HEADER:
                return new HeaderHolder(inflate(context, R.layout.cell_header, parent, false));
            case TYPE_PIN:
                return new PinViewHolder(inflate(context, R.layout.cell_pin, parent, false));
            case TYPE_LINK:
                return new LinkHolder(inflate(context, R.layout.cell_link, parent, false));
        }
        throw new IllegalArgumentException();
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        switch (holder.getItemViewType()) {
            case TYPE_PIN:
                final Pin pin = watchManager.getAllPins().get(position - PIN_OFFSET);
                PinViewHolder pinHolder = (PinViewHolder) holder;
                updatePinViewHolder(pinHolder, pin);

                break;
            case TYPE_LINK:
                LinkHolder linkHolder = (LinkHolder) holder;
                switch (position) {
                    case 0:
                        linkHolder.text.setText(R.string.drawer_settings);
                        linkHolder.image.setImageResource(R.drawable.ic_settings_themed_24dp);
                        updateNotificationIcon(linkHolder);
                        break;
                    case 1:
                        linkHolder.text.setText(R.string.drawer_history);
                        linkHolder.image.setImageResource(R.drawable.ic_history_themed_24dp);
                        break;
                }
                break;
            case TYPE_HEADER:
            default:
                break;
        }
    }

    private void updateNotificationIcon(LinkHolder linkHolder) {
        SettingNotificationType notificationType = settingsNotificationManager.getNotificationByPriority();

        String notificationTypeString = "null";
        if (notificationType != null) {
            notificationTypeString = notificationType.name();
        }

        Logger.d(this, "updateNotificationIcon() called notificationType = " + notificationTypeString);

        if (notificationType != null) {
            int color = getRes().getColor(notificationType.getNotificationIconTintColor());

            linkHolder.notificationIcon.setVisibility(VISIBLE);
            linkHolder.notificationIcon.setColorFilter(color);

            int totalNotificationsCount = settingsNotificationManager.notificationsCount();
            if (totalNotificationsCount > 1) {
                linkHolder.totalNotificationsCount.setVisibility(VISIBLE);
                linkHolder.totalNotificationsCount.setText(String.valueOf(totalNotificationsCount));
            } else {
                linkHolder.totalNotificationsCount.setVisibility(GONE);
            }
        } else {
            linkHolder.notificationIcon.setVisibility(GONE);
            linkHolder.totalNotificationsCount.setVisibility(GONE);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder.getItemViewType() == TYPE_PIN) {
            PinViewHolder pinViewHolder = (PinViewHolder) holder;
            if (pinViewHolder.threadDownloadIcon.getDrawable() instanceof AnimatedVectorDrawableCompat) {
                AnimatedVectorDrawableCompat downloadIcon =
                        (AnimatedVectorDrawableCompat) pinViewHolder.threadDownloadIcon.getDrawable();
                downloadIcon.stop();
                downloadIcon.clearAnimationCallbacks();
            }
        }
    }

    @Override
    public int getItemCount() {
        return watchManager.getAllPins().size() + PIN_OFFSET;
    }

    @Override
    public long getItemId(int position) {
        position -= PIN_OFFSET;
        if (position >= 0 && position < watchManager.getAllPins().size()) {
            return watchManager.getAllPins().get(position).id + 10;
        } else {
            return position;
        }
    }

    @Override
    public int getItemViewType(int position) {
        switch (position) {
            case 0:
            case 1:
                return TYPE_LINK;
            case 2:
                return TYPE_HEADER;
            default:
                return TYPE_PIN;
        }
    }

    public void onNotificationsChanged() {
        BackgroundUtils.ensureMainThread();
        notifyItemChanged(SETTINGS_OFFSET);
    }

    public void onPinAdded(Pin pin) {
        notifyItemInserted(watchManager.getAllPins().indexOf(pin) + PIN_OFFSET);
    }

    public void onPinRemoved(int index) {
        notifyItemRemoved(index + PIN_OFFSET);
    }

    public void onPinChanged(RecyclerView recyclerView, Pin pin) {
        PinViewHolder holder = (PinViewHolder) recyclerView.findViewHolderForAdapterPosition(
                watchManager.getAllPins().indexOf(pin) + PIN_OFFSET);
        if (holder != null) {
            updatePinViewHolder(holder, pin);
            notifyItemChanged(watchManager.getAllPins().indexOf(pin) + PIN_OFFSET);
        }
    }

    public void updateHighlighted(RecyclerView recyclerView) {
        for (int i = 0; i < watchManager.getAllPins().size(); i++) {
            PinViewHolder holder = (PinViewHolder) recyclerView.findViewHolderForAdapterPosition(i + PIN_OFFSET);
            if (holder != null) {
                updatePinViewHolder(holder, watchManager.getAllPins().get(i));
                notifyItemChanged(i + PIN_OFFSET);
            }
        }
    }

    private void updatePinViewHolder(PinViewHolder holder, Pin pin) {
        TextView watchCount = holder.watchCountText;

        CharSequence text = pin.loadable.title;
        if (pin.archived) {
            text = PostHelper.prependIcon(context, text, archivedIcon, sp(16));
        }

        if(pin.isSticky) {
            text = PostHelper.prependIcon(context, text, stickyIcon, sp(16));
        }

        TextView bookmarkLabel = holder.textView;
        bookmarkLabel.setText(text);
        loadBookmarkImage(holder, pin);
        holder.image.setGreyscale(!pin.watching);

        if (ChanSettings.watchEnabled.get()) {
            if (PinType.hasWatchNewPostsFlag(pin.pinType)) {
                WatchManager.PinWatcher pinWatcher = watchManager.getPinWatcher(pin);
                if (pinWatcher != null) {
                    updatePinViewHolderInternal(pin, watchCount, pinWatcher);
                }
            } else {
                watchCount.setVisibility(GONE);
            }
        } else {
            watchCount.setVisibility(GONE);
            holder.threadDownloadIcon.setVisibility(GONE);
        }

        setPinDownloadIcon(holder, pin);

        boolean highlighted = pin == this.highlighted;
        if (highlighted && !holder.highlighted) {
            holder.itemView.setBackgroundColor(getAttrColor(context, R.attr.highlight_divider_color));
            holder.highlighted = true;
        } else if (!highlighted && holder.highlighted) {
            Drawable attrDrawable =
                    getAttrDrawable(holder.itemView.getContext(), android.R.attr.selectableItemBackground);

            holder.itemView.setBackground(attrDrawable);
            holder.highlighted = false;
        }
    }

    private void updatePinViewHolderInternal(Pin pin, TextView watchCount, WatchManager.PinWatcher pinWatcher) {
        String newCount = PinHelper.getShortUnreadCount(pin.getNewPostCount());
        //use the pin's watch count if the thread hasn't been loaded yet, otherwise use the latest reply count from the loaded thread
        int postsCount = pinWatcher.lastReplyCount > 0 ? pinWatcher.lastReplyCount : pin.watchNewCount - 1;
        String totalCount = PinHelper.getShortUnreadCount(postsCount);

        String watchCountText = ChanSettings.shortPinInfo.get() ? newCount : totalCount + " / " + newCount;

        watchCount.setText(watchCountText);
        watchCount.setVisibility(View.VISIBLE);

        if (pin.getNewQuoteCount() > 0) {
            watchCount.setTextColor(getColor(R.color.pin_posts_has_replies_color));
        } else if (!pin.watching) {
            watchCount.setTextColor(getColor(R.color.pin_posts_not_watching_color));
        } else {
            watchCount.setTextColor(getColor(R.color.pin_posts_normal_color));
        }

        watchCount.setTypeface(watchCount.getTypeface(), Typeface.NORMAL);
        watchCount.setPaintFlags(Paint.ANTI_ALIAS_FLAG);
        Board pinBoard = pin.loadable.board;
        boolean italicize = false, bold = false;
        //use the pin's watch count if the thread hasn't been loaded yet, otherwise use the latest reply count from the loaded thread
        if ((pinWatcher.lastReplyCount > 0 ? pinWatcher.lastReplyCount : pin.watchNewCount - 1) >= pinBoard.bumpLimit
                && pinBoard.bumpLimit > 0) {
            //italics for bump limit, if not a sticky
            italicize = !pinWatcher.getIsSticky();
        }

        if (pinWatcher.getImageCount() >= pinBoard.imageLimit && pinBoard.imageLimit > 0) {
            //bold for image limit, if not a sticky
            bold = !pinWatcher.getIsSticky();
        }

        if (italicize && bold) {
            watchCount.setTypeface(watchCount.getTypeface(), Typeface.BOLD_ITALIC);
        } else if (italicize) {
            watchCount.setTypeface(watchCount.getTypeface(), Typeface.ITALIC);
        } else if (bold) {
            watchCount.setTypeface(watchCount.getTypeface(), Typeface.BOLD);
        }

        if (pinWatcher.latestKnownPage >= pinBoard.pages && pinBoard.pages > 0) {
            //underline for page limit
            watchCount.setPaintFlags(watchCount.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        }

        watchCount.setPaintFlags(watchCount.getPaintFlags());
    }

    private void loadBookmarkImage(PinViewHolder holder, Pin pin) {
        SavedThread savedThread = null;

        if (PinType.hasDownloadFlag(pin.pinType)) {
            savedThread = watchManager.findSavedThreadByLoadableId(pin.loadable.id);
        }

        if (savedThread == null || !savedThread.isFullyDownloaded) {
            holder.image.setUrl(pin.thumbnailUrl, dp(48), dp(48));
            return;
        }

        String filename = StringUtils.convertThumbnailUrlToFilenameOnDisk(pin.thumbnailUrl);
        if (filename == null || filename.isEmpty()) {
            holder.image.setUrl(pin.thumbnailUrl, dp(48), dp(48));
            return;
        }

        holder.image.setUrlFromDisk(pin.loadable, filename, false, dp(48), dp(48));
    }

    private void setPinDownloadIcon(PinViewHolder holder, Pin pin) {
        SavedThread savedThread = null;

        if (PinType.hasDownloadFlag(pin.pinType)) {
            savedThread = watchManager.findSavedThreadByLoadableId(pin.loadable.id);
        }

        if (savedThread == null) {
            holder.threadDownloadIcon.setVisibility(GONE);
            return;
        }

        holder.threadDownloadIcon.setVisibility(VISIBLE);

        if (savedThread.isFullyDownloaded) {
            if (holder.threadDownloadIcon.getDrawable() instanceof AnimatedVectorDrawableCompat) {
                AnimatedVectorDrawableCompat drawable =
                        (AnimatedVectorDrawableCompat) holder.threadDownloadIcon.getDrawable();
                drawable.stop();
                drawable.clearAnimationCallbacks();
            }

            holder.threadDownloadIcon.setImageDrawable(downloadIconFilled);
            return;
        }

        if (savedThread.isStopped) {
            if (holder.threadDownloadIcon.getDrawable() instanceof AnimatedVectorDrawableCompat) {
                AnimatedVectorDrawableCompat drawable =
                        (AnimatedVectorDrawableCompat) holder.threadDownloadIcon.getDrawable();
                drawable.stop();
                drawable.clearAnimationCallbacks();
            }

            holder.threadDownloadIcon.setImageDrawable(downloadIconOutline);
            return;
        }

        if (!(holder.threadDownloadIcon.getDrawable() instanceof AnimatedVectorDrawableCompat)) {
            AnimatedVectorDrawableCompat downloadAnimation = AnimationUtils.createAnimatedDownloadIcon(context,
                    getAttrColor(context, android.R.attr.textColorPrimary)
            );
            holder.threadDownloadIcon.setImageDrawable(downloadAnimation);

            downloadAnimation.start();
            downloadAnimation.registerAnimationCallback(new Animatable2Compat.AnimationCallback() {
                @Override
                public void onAnimationEnd(Drawable drawable) {
                    super.onAnimationEnd(drawable);

                    downloadAnimation.start();
                }
            });
        }
    }

    private class PinViewHolder
            extends RecyclerView.ViewHolder {
        private boolean highlighted;
        private ThumbnailView image;
        private TextView textView;
        private TextView watchCountText;
        private ImageView threadDownloadIcon;

        private PinViewHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.thumb);
            image.setCircular(true);
            textView = itemView.findViewById(R.id.text);
            textView.setTypeface(ThemeHelper.getTheme().mainFont);
            watchCountText = itemView.findViewById(R.id.watch_count);
            watchCountText.setTypeface(ThemeHelper.getTheme().mainFont);
            threadDownloadIcon = itemView.findViewById(R.id.thread_download_icon);

            image.setOnClickListener(v -> {
                int pos = getAdapterPosition() - PIN_OFFSET;
                if (pos >= 0 && pos < watchManager.getAllPins().size()) {
                    callback.onWatchCountClicked(watchManager.getAllPins().get(pos));
                }
            });

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition() - PIN_OFFSET;
                if (pos >= 0 && pos < watchManager.getAllPins().size()) {
                    callback.onPinClicked(watchManager.getAllPins().get(pos));
                }
            });

            watchCountText.setOnClickListener(v -> {
                int pos = getAdapterPosition() - PIN_OFFSET;
                if (pos >= 0 && pos < watchManager.getAllPins().size()) {
                    callback.onWatchCountClicked(watchManager.getAllPins().get(pos));
                }
            });
        }
    }

    public class HeaderHolder
            extends RecyclerView.ViewHolder {

        private HeaderHolder(View itemView) {
            super(itemView);
            TextView text = itemView.findViewById(R.id.text);
            text.setTypeface(ThemeHelper.getTheme().mainFont);
            ImageView clear = itemView.findViewById(R.id.clear);
            clear.setOnClickListener(v -> callback.onHeaderClicked(HeaderAction.CLEAR));
            clear.setOnLongClickListener(v -> {
                callback.onHeaderClicked(HeaderAction.CLEAR_ALL);
                return true;
            });
        }
    }

    public enum HeaderAction {
        CLEAR,
        CLEAR_ALL
    }

    private class LinkHolder
            extends RecyclerView.ViewHolder {
        private ImageView image;
        private TextView text;
        private ImageView notificationIcon;
        private TextView totalNotificationsCount;

        private LinkHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.image);
            text = itemView.findViewById(R.id.text);
            text.setTypeface(ThemeHelper.getTheme().mainFont);
            notificationIcon = itemView.findViewById(R.id.setting_notification_icon);
            totalNotificationsCount = itemView.findViewById(R.id.setting_notification_total_count);
            updatePaddings(notificationIcon, dp(4), dp(4), dp(4), dp(4));

            itemView.setOnClickListener(v -> {
                switch (getAdapterPosition()) {
                    case 0:
                        callback.openSettings();
                        break;
                    case 1:
                        callback.openHistory();
                        break;
                }
            });
        }
    }

    public interface Callback {
        void onPinClicked(Pin pin);

        void onWatchCountClicked(Pin pin);

        void onHeaderClicked(HeaderAction headerAction);

        void onPinRemoved(Pin pin);

        void openSettings();

        void openHistory();
    }
}
