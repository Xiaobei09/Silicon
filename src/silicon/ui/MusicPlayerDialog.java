package silicon.ui;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.CheckBox;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import silicon.audio.MusicNetwork;
import silicon.audio.MusicPlayer;
import silicon.audio.MusicTrack;

import static mindustry.Vars.ui;

/**
 * 音乐播放器弹窗：完整的播放控制 + 曲目库管理。
 * 入口：设置菜单「音乐播放器」按钮、快捷键、悬浮条展开。
 */
public class MusicPlayerDialog extends BaseDialog {
    private static MusicPlayerDialog instance;

    /** 弹窗内文字按钮的统一高度（原来各处 30/32/34/36/38/40/48 混用，同一界面上按钮大小不一） */
    private static final float BTN_H = 34f;

    private Table trackTable;
    /** 主播放/暂停按钮的引用：供 MusicPlayer 每帧推送刷新（元素自身 update 不可靠，见 MusicBar.syncNow 注释） */
    private ImageButton ppBtn;

    /** 由 MusicPlayer 每帧调用：把播放/起播态直接刷到主按钮上（弹窗没开时是无害的空操作）。
     *  图标同步交给 {@link MusicBar#applyPlayIcon}：它改的是按钮**自己的 style 副本**——
     *  arc 的 ImageButton.draw() 每帧会用 style.imageUp 覆盖子 Image，光改子元素不生效（已实测）。 */
    public static void syncPlayButtonNow() {
        MusicPlayerDialog d = instance;
        if (d == null || d.ppBtn == null || !d.isShown() || d.getScene() != Core.scene) return;
        MusicBar.applyPlayIcon(d.ppBtn);
    }
    /** 当前专辑筛选（null = 全部曲目） */
    private String filterAlbum = null;
    private String filterText = "";

    /**
     * 弹窗内文字按钮的**统一格式**：flatBordert + 默认字号（不加 setFontScale）+ 关换行/省略号。
     * <p>
     * 之前每个按钮各写各的：循环/倒放/重置位置 单独设了 0.9 字号（文字明显比停止/添加曲目小），
     * 高度也在 30~48 之间散落。凡「普通动作按钮」一律走这里，避免再出现同类不一致。
     * 专辑名按钮例外：长名必须裁剪，调用后自行 setEllipsis(true)。
     */
    private static TextButton textBtn(String label, Runnable action) {
        TextButton b = new TextButton(label, Styles.flatBordert);
        b.getLabel().setWrap(false);
        b.getLabel().setEllipsis(false);
        if (action != null) b.clicked(action);
        return b;
    }

    public static void open() {
        if (instance == null || !instance.isShown() || instance.getScene() != Core.scene) {
            instance = new MusicPlayerDialog();
        }
        instance.show();
        instance.rebuild();
    }

    /** 下载完成/分块收齐后刷新曲目信息（时长/大小）显示：弹窗开着则重建曲目行，无需重开弹窗 */
    public static void refreshIfOpen() {
        if (instance != null && instance.isShown() && instance.getScene() == Core.scene) {
            instance.rebuildRows();
        }
    }

    private MusicPlayerDialog() {
        super(Core.bundle.get("musicplayer.title"));
        closeOnBack();
    }

    private void rebuild() {
        cont.clearChildren();
        cont.top();
        // 内容区铺底色：defaultDialog 的背景是 Tex.windowEmpty（只画边框、中间不铺底），
        // 各内部面板之间/之外的空白会透出游戏画面——即「背景不能完全覆盖」。
        // 用**半透明**黑（black6 = 60% 黑）铺满内容区：既盖住露底，又能透出后面的游戏画面，
        // 与游戏里其它悬浮/弹窗面板观感一致（此前一版用的是不透明的 grayPanel）。
        cont.background(Styles.black6);

        // —— 顶部「现在播放」面板（实时刷新：状态/曲名随播放变化自动更新） ——
        cont.table(now -> {
            now.background(Styles.grayPanel);
            now.margin(12f);
            now.defaults().top();

            // 左侧：状态图标圆盘
            final arc.scene.ui.Image[] disc = new arc.scene.ui.Image[1];
            now.table(d -> {
                d.background(Styles.grayPanelDark);
                d.margin(9f);
                disc[0] = new arc.scene.ui.Image(MusicPlayer.isPlaying() ? Icon.pause : Icon.play);
                disc[0].setSize(Scl.scl(24f));
                disc[0].setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.lightGray);
                d.add(disc[0]).size(24f).color(MusicPlayer.isPlaying() ? Pal.accent : Color.lightGray);
            }).padRight(14f);

            // 右侧：状态标题 + 曲名
            final arc.scene.ui.Label[] stateLbl = new arc.scene.ui.Label[1];
            final MusicBar.MarqueeLabel[] nameLbl = new MusicBar.MarqueeLabel[1];
            now.table(info -> {
                info.defaults().left();
                stateLbl[0] = new arc.scene.ui.Label(MusicPlayer.isPlaying()
                                ? Core.bundle.get("musicplayer.playing")
                                : Core.bundle.get("musicplayer.play"),
                        Styles.outlineLabel);
                stateLbl[0].setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.lightGray);
                info.add(stateLbl[0]);
                info.row();
                nameLbl[0] = new MusicBar.MarqueeLabel(nowPlayingLabel(), Styles.outlineLabel);
                nameLbl[0].setColor(MusicPlayer.isPlaying() ? Color.white : Color.lightGray);
                // 列宽必须显式封顶（arc Cell.width 同时钳制 min/max）：MarqueeLabel.getPrefWidth 会泄漏
                // 真实文本宽（上限 maxPref=520），若不钳制，长曲名会把「现在播放」面板撑到超出弹窗宽度，
                // 行右端被裁切 →「名字不全/有空间仍循环」。520 为弹窗宽度减去圆盘/边距后的可用宽。
                // 给足高度 + MarqueeLabel 内部垂直居中，避免名称上半部分被裁切
                // 适配屏幕：曲名可用宽取屏幕宽的 32%（上限 520 Scl），窄屏下弹窗不会被长曲名撑破
                // 同一份「像素预算」同时给 maxPref 与 Cell.width：Cell.width 内部还会再乘一次 Scl，
                // 所以这里要除以 Scl 换算成 cell 单位，否则 maxPref 形同虚设、窄屏无法收窄（整窗被撑破）。
                float namePx = Math.min(Scl.scl(520f), Core.graphics.getWidth() * 0.32f);
                nameLbl[0].maxPref = namePx;
                info.add(nameLbl[0]).growX().width(namePx / Scl.scl(1f)).height(24f).padRight(6f);
            }).growX();
            // 每帧刷新状态与曲名（悬浮条/自动推进切换曲目时这里也跟着变）；仅内容变化时 setText 避免反复重排。
            // 三态：播放中 / 启动中（转码解封装，按钮看着没反应的那段时间）/ 未播放
            final String[] lastNow = {""};
            now.update(() -> {
                boolean playing = MusicPlayer.isPlaying();
                boolean starting = !playing && MusicPlayer.isStarting();
                String key = (playing ? "P" : starting ? "T" : "S") + "|" + nowPlayingLabel();
                if (key.equals(lastNow[0])) return;
                lastNow[0] = key;
                disc[0].setDrawable(playing || starting ? Icon.pause : Icon.play);
                disc[0].setColor(playing ? Pal.accent : Color.lightGray);
                stateLbl[0].setText(playing ? Core.bundle.get("musicplayer.playing")
                        : starting ? Core.bundle.get("musicplayer.decoding")
                        : Core.bundle.get("musicplayer.play"));
                stateLbl[0].setColor(playing ? Pal.accent : Color.lightGray);
                nameLbl[0].setText(nowPlayingLabel());
                nameLbl[0].setColor(playing ? Color.white : Color.lightGray);
            });
        }).growX().padBottom(8f).row();

        // —— 播放进度条（可拖动选进度） ——
        cont.table(seek -> {
            seek.background(Styles.grayPanelDark);
            seek.margin(4f, 8f, 4f, 8f);
            seek.defaults().left();
            final arc.scene.ui.Label time = new arc.scene.ui.Label("0:00 / 0:00", Styles.outlineLabel);
            time.setColor(Color.white);
            // 时间标签固定宽度（arc Cell.width 钳制 min/max）：m:ss 每秒变化字宽不同，若随内容伸缩，
            // 同行的进度条每秒被挤压/回弹（「进度条抖动」根因之一），且 setText 触发整表重排。
            seek.add(time).width(120f).left();
            Slider seekBar = new MusicBar.AbSlider();
            seekBar.setDisabled(true);
            final boolean[] userSeek = {false};
            final float[] lastShown = {Float.NEGATIVE_INFINITY};
            seekBar.update(() -> {
                float len = MusicPlayer.trackLength();
                // 修复：未知长度(-1)、异常大值(>12h)或该声源 seek 已被判不可靠时禁用拖动
                boolean hasLen = len > 0f && len < 12f * 3600f && !MusicPlayer.isSeekUnreliable();
                // 已知时长即可拖动（播放/暂停皆可）；拖动中不刷新值避免回跳摇动，拖动时时间标签预览拖动位置
                seekBar.setDisabled(!hasLen);
                if (hasLen) {
                    float cur = seekBar.isDragging() ? seekBar.getValue() * len : MusicPlayer.currentTime();
                    // 抖动修复：仅内容变化时 setText（每帧无条件 setText 会触发整弹窗逐帧重排，
                    // 造成进度条/按钮集体抖动）
                    String s = MusicPlayer.decodeProgressText();
                    if (s == null) s = formatTime(cur, len);
                    if (!s.equals(time.getText().toString())) time.setText(s);
                    // 抖动修复：非拖动且与上次显示值差异超过阈值才重设，避免每帧原地重设导致指针抖动
                    if (!userSeek[0] && !seekBar.isDragging()) {
                        float target = cur / len;
                        if (Math.abs(target - lastShown[0]) > 0.0005f) {
                            userSeek[0] = true;
                            seekBar.setValue(target);
                            lastShown[0] = target;
                            userSeek[0] = false;
                        }
                    } else if (seekBar.isDragging()) {
                        lastShown[0] = seekBar.getValue();
                    }
                } else {
                    // 未知/超大时长：显示当前进度 / --:--，与悬浮条文案一致，避免 0:00/0:00 误导
                    String s = MusicPlayer.decodeProgressText();
                    if (s == null) s = formatTime(MusicPlayer.currentTime(), len);
                    if (!s.equals(time.getText().toString())) time.setText(s);
                }
            });
            seekBar.changed(() -> {
                float len = MusicPlayer.trackLength();
                if (!userSeek[0] && len > 0f && len < 12f * 3600f && !MusicPlayer.isSeekUnreliable()) {
                    userSeek[0] = true;
                    MusicPlayer.seek(seekBar.getValue() * len);
                    lastShown[0] = seekBar.getValue();
                    userSeek[0] = false;
                }
            });
            seek.add(seekBar).growX().padLeft(10f);
        }).growX().padBottom(4f).row();

        // —— 主控制条：上一首 / 快退 / 播放暂停 / 快进 / 下一首（图标按钮，高度统一） ——
        cont.table(ctrl -> {
            ctrl.button(Icon.leftOpen, Styles.flati, MusicPlayer::prev)
                    .growX().height(48f).padRight(2f);
            ctrl.button(Icon.leftSmall, Styles.flati, () -> MusicPlayer.seekRelative(-10f))
                    .growX().height(48f).pad(2f);
            ImageButton pp = new ImageButton(MusicPlayer.isPlaying() ? Icon.pause : Icon.play, MusicBar.playStyle(Styles.flati));
            ppBtn = pp; // 供 MusicPlayer 每帧推送刷新（见 syncPlayButtonNow）
            pp.resizeImage(Scl.scl(26f));
            pp.getImage().setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.white);
            // 修复（2026-09-03 rev5）：主播放/暂停按钮图标此前只在「点击 togglePlay→rebuild」时刷新，
            // 若播放态经自动推进/倒放回开头停/远端状态变化等非点击路径改变，图标会滞留旧状态。
            // 与悬浮条 playButtonFrameSync 一致，每帧同步到真实播放态，彻底根治图标滞旧。
            // 另加「启动中」态（转码/解封装期间 isPlaying 仍 false）：画暂停图标并压暗，
            // 否则点播放后长曲解码的几秒~几十秒内按钮毫无变化，看着像没反应。
            pp.update(() -> {
                boolean p = MusicPlayer.isPlaying();
                boolean starting = !p && MusicPlayer.isStarting();
                pp.getImage().setDrawable(p || starting ? Icon.pause : Icon.play);
                pp.getImage().setColor(p ? Pal.accent : (starting ? Color.lightGray : Color.white));
            });
            pp.clicked(this::togglePlay);
            ctrl.add(pp).growX().height(48f).pad(2f);
            ctrl.button(Icon.rightSmall, Styles.flati, () -> MusicPlayer.seekRelative(10f))
                    .growX().height(48f).pad(2f);
            ctrl.button(Icon.rightOpen, Styles.flati, MusicPlayer::next)
                    .growX().height(48f).padLeft(2f);
        }).growX().padTop(2f).row();

        // —— 专辑筛选栏 ——
        // 专辑多时（8~10 个以上）横排放不下：整行会越推越宽、把弹窗挤出屏幕。
        // 这里把这一行放进**横向可滚动**的 ScrollPane（纵向禁用），专辑再多也只是横向滚动。
        Table albumsRow = new Table();
        {
            Table albumsFilter = albumsRow;
            albumsFilter.background(Styles.grayPanel);
            albumsFilter.margin(3f, 6f, 3f, 6f);
            // 「全部曲目」按钮
            TextButton all = textBtn(Core.bundle.get("musicplayer.allAlbums"), null);
            all.update(() -> all.setColor(filterAlbum == null ? Pal.accent : Color.lightGray));
            all.clicked(() -> { filterAlbum = null; rebuildRows(); });
            albumsFilter.add(all).width(100f).height(BTN_H).pad(1f);
            Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
            for (int i = 0; i < albums.size; i++) {
                MusicPlayer.Album a = albums.get(i);
                final String name = a.name;
                TextButton b = textBtn(a.name, null);
                b.getLabel().setEllipsis(true); // 专辑名可能很长：这一处需要裁剪（唯一例外）
                b.update(() -> b.setColor(name.equals(filterAlbum) ? Pal.accent : Color.white));
                b.clicked(() -> { filterAlbum = name; rebuildRows(); });
                albumsFilter.add(b).width(96f).height(BTN_H).pad(1f);
            }
            // 删除当前筛选专辑按钮（仅 filterAlbum 非空时有效）
            ImageButton delAlbum = new ImageButton(Icon.trash, Styles.cleari);
            delAlbum.resizeImage(Scl.scl(15f));
            delAlbum.clicked(() -> deleteCurrentAlbum());
            albumsFilter.add(delAlbum).size(30f).padLeft(6f);
        }
        ScrollPane albumScroll = new ScrollPane(albumsRow, Styles.defaultPane);
        albumScroll.setScrollingDisabled(false, true); // 允许横向滚动、禁止纵向
        albumScroll.setFadeScrollBars(false);
        cont.add(albumScroll).growX().height(BTN_H + 12f).padTop(6f).row();
        // 新专辑按钮固定在这一行右端（不随专辑数左右移动）
        cont.table(row -> {
            row.add().growX();
            row.button(Icon.add, Styles.cleari, this::newAlbumDialog).size(30f).padLeft(4f);
        }).growX().padTop(2f).row();

        // —— 音量 / 倍速（两个并排面板合为一行；2026-09-03 移除独立音高面板：Soloud 无独立音调控制、
        //    变速不变调需引入 ffmpeg，故只保留一个「倍速」控制，变速即变调，自然声学） ——
        cont.table(analog -> {
            analog.defaults().pad(2f);
            // 音量面板（0–1000%，100% 在条中间、两侧刻度不同：左半 0–100%、右半 100–1000%；
            // 指数映射到内部增益 0–10x，100%＝0dB＝增益1）
            analog.table(vp -> {
                vp.background(Styles.grayPanel);
                vp.margin(6f);
                vp.defaults().pad(2f);
                arc.scene.ui.Image volIcon = new arc.scene.ui.Image(Icon.chat);
                volIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("音量 0-1000%")));
                vp.add(volIcon).size(14f).padRight(2f);
                vp.add(Core.bundle.get("musicplayer.volume")).left().width(42f).padRight(2f);
                Slider vol = new Slider(0f, 1000f, 1f, false);
                vol.setValue(pctToVol(gainToPct(MusicPlayer.volume())));
                final arc.scene.ui.Label volVal = new arc.scene.ui.Label(pctText(gainToPct(MusicPlayer.volume())), Styles.outlineLabel);
                volVal.setColor(Color.white);
                vol.update(() -> {
                    float target = pctToVol(gainToPct(MusicPlayer.volume()));
                    if (!vol.isDragging() && Math.abs(vol.getValue() - target) > 1f) {
                        vol.setValue(target);
                    }
                    // 仅内容变化时 setText（每帧 setText 触发整弹窗重排=按钮抖动）
                    String s = pctText(volToPct(vol.getValue()));
                    if (!s.equals(volVal.getText().toString())) volVal.setText(s);
                });
                vol.changed(() -> MusicPlayer.setVolume(pctToGain(volToPct(vol.getValue()))));
                vp.add(vol).growX().width(170f);
                vp.add(volVal).width(62f).right().padLeft(4f);
            }).growX();
            // 倍速面板
            analog.table(sp -> {
                sp.background(Styles.grayPanel);
                sp.margin(6f);
                sp.defaults().pad(2f);
                arc.scene.ui.Image spdIcon = new arc.scene.ui.Image(Icon.rightSmall);
                spdIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("倍速 1/16-16x")));
                sp.add(spdIcon).size(14f).padRight(2f);
                sp.add(Core.bundle.get("musicplayer.speed")).left().width(38f).padRight(2f);
                Slider spd = new Slider(0f, 1f, 0.001f, false);
                spd.setValue(speedToCursor(MusicPlayer.speed()));
                final arc.scene.ui.Label spdVal = new arc.scene.ui.Label(formatSpeed(MusicPlayer.speed()), Styles.outlineLabel);
                spdVal.setColor(Color.white);
                spd.update(() -> {
                    if (!spd.isDragging() && Math.abs(cursorToSpeed(spd.getValue()) - MusicPlayer.speed()) > 0.001f) {
                        spd.setValue(speedToCursor(MusicPlayer.speed()));
                    }
                    // 仅内容变化时 setText（每帧 setText 触发整弹窗重排=按钮抖动）
                    String s = formatSpeed(cursorToSpeed(spd.getValue()));
                    if (!s.equals(spdVal.getText().toString())) spdVal.setText(s);
                });
                spd.changed(() -> MusicPlayer.setSpeed(cursorToSpeed(spd.getValue())));
                sp.add(spd).growX().width(120f);
                sp.add(spdVal).width(64f).right().padLeft(4f);
            }).growX();
        }).growX().padTop(2f).row();

        // —— A-B 区间（区间重复）：设 A / 设 B / 清除 + 区间状态与范围 ——
        cont.table(abRow -> {
            abRow.background(Styles.grayPanel);
            abRow.margin(5f, 8f, 5f, 8f);
            abRow.defaults().pad(2f);
            arc.scene.ui.Image abIcon = new arc.scene.ui.Image(Icon.book);
            abIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("A-B 区间重复")));
            abRow.add(abIcon).size(14f).padRight(2f);
            abRow.add(Core.bundle.get("musicplayer.ab")).left().width(76f);
            final arc.scene.ui.Label abStatus = new arc.scene.ui.Label(abStatusText(), Styles.outlineLabel);
            abStatus.setColor(MusicPlayer.hasAb() ? Pal.accent : Color.lightGray);
            final String[] lastAbTxt = {abStatusText()};
            abStatus.update(() -> {
                // 仅内容/状态变化时更新（每帧 setText 触发整弹窗重排=按钮抖动）
                String t = abStatusText();
                if (!t.equals(lastAbTxt[0])) {
                    lastAbTxt[0] = t;
                    abStatus.setText(t);
                    abStatus.setColor(MusicPlayer.hasAb() ? Pal.accent : Color.lightGray);
                }
            });
            abRow.add(abStatus).growX().left().padLeft(2f);
            TextButton abA = textBtn(Core.bundle.get("musicplayer.abSetA"), () -> {
                MusicPlayer.setAbA(MusicPlayer.currentTime());
                rebuild();
            });
            abRow.add(abA).height(BTN_H).width(86f);
            TextButton abB = textBtn(Core.bundle.get("musicplayer.abSetB"), () -> {
                MusicPlayer.setAbB(MusicPlayer.currentTime());
                rebuild();
            });
            abRow.add(abB).height(BTN_H).width(86f);
            TextButton abC = textBtn(Core.bundle.get("musicplayer.abClear"), () -> {
                MusicPlayer.clearAb();
                rebuild();
            });
            abRow.add(abC).height(BTN_H).width(86f);
        }).growX().padTop(2f).row();

        // —— 底部：循环模式 / 倒放 / 停止 / 添加曲目（图标点缀提升可识别性） ——
        // 四个按钮一律走 textBtn(BTN_H)：同一字号（此前循环/倒放单独设了 0.9 字号，明显比停止/添加曲目小）、
        // 同一高度与间距、关闭换行与省略号。固定宽度而非 growX（不等长文本切换不会让按钮忽大忽小）。
        cont.table(bottom -> {
            arc.scene.ui.Image botIcon = new arc.scene.ui.Image(Icon.music);
            botIcon.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("播放控制")));
            bottom.add(botIcon).size(14f).padRight(4f);
            TextButton loop = textBtn(loopModeText(), null);
            // 点击即更新文案（固定宽的等长两字中文，不必等下一帧重建）
            loop.clicked(() -> {
                MusicPlayer.cycleLoopMode();
                loop.setText(loopModeText());
            });
            // 外部改变循环模式时（悬浮条上的循环按钮）也要跟着变，否则弹窗里会停在旧文案
            final String[] lastLoopTxt = {loopModeText()};
            loop.update(() -> {
                String txt = loopModeText();
                if (!lastLoopTxt[0].equals(txt)) {
                    lastLoopTxt[0] = txt;
                    loop.setText(txt);
                }
            });
            bottom.add(loop).width(84f).height(BTN_H).pad(2f);

            TextButton rev = textBtn(Core.bundle.get("musicplayer.reverse"), MusicPlayer::toggleReverse);
            rev.update(() -> rev.getLabel().setColor(MusicPlayer.isReverse() ? Pal.accent : Color.white));
            bottom.add(rev).width(84f).height(BTN_H).pad(2f);

            TextButton stop = textBtn(Core.bundle.get("musicplayer.stop"), MusicPlayer::stop);
            bottom.add(stop).width(96f).height(BTN_H).pad(2f);

            TextButton add = textBtn(Core.bundle.get("musicplayer.addTrack"), this::showAddDialog);
            bottom.add(add).width(112f).height(BTN_H).pad(2f);
        }).growX().padTop(2f).row();

        // —— 更多设置：播放给他人 / 总开关 / 悬浮条复位 ——
        // 两个勾选框**分行并各带说明**：原先并排挤在一行、只靠图标区分，总开关被误点后唯一可见效果
        // 就是「悬浮条消失」（音乐照常播放），用户完全找不到原因。分行 + 说明文字后一眼能看懂。
        cont.table(more -> {
            more.background(Styles.grayPanel);
            more.margin(4f, 8f, 4f, 8f);
            more.defaults().pad(2f).left();

            more.table(row -> {
                // 不再在前面放图标（用户要求去掉）：勾选框本身已够清楚，图标只占宽
                CheckBox share = new CheckBox(Core.bundle.get("musicplayer.share"));
                share.setChecked(MusicPlayer.isShareEnabled());
                share.changed(() -> MusicPlayer.setShareEnabled(share.isChecked()));
                row.add(share).left();
                row.add(Core.bundle.get("musicplayer.shareHint")).color(Color.lightGray)
                        .fontScale(0.85f).padLeft(8f).left();
            }).growX().left().row();

            more.table(row -> {
                CheckBox enable = new CheckBox(Core.bundle.get("musicplayer.enable"));
                enable.setChecked(MusicPlayer.isEnabled());
                enable.changed(() -> MusicPlayer.setEnabled(enable.isChecked()));
                row.add(enable).left();
                // 总开关的后果写清楚（关掉后悬浮条会隐藏），避免再次出现「条怎么不见了」
                row.add(Core.bundle.get("musicplayer.enableHint")).color(Pal.accent)
                        .fontScale(0.85f).padLeft(8f).left();
            }).growX().left().row();

            more.table(row -> {
                // 重置位置：此前单独设了 0.9 字号（比同界面其他按钮文字小），现与其它动作按钮统一
                TextButton reset = textBtn(Core.bundle.get("musicplayer.resetPos"), () -> MusicBar.resetPosition());
                row.add(reset).height(BTN_H).width(150f).right();
            }).growX().right().row();
        }).growX().padTop(2f).row();

        // —— 曲目列表（置于底部并 growY 填满剩余高度，消除设置界面下方空白） ——
        trackTable = new Table();
        trackTable.top();
        trackTable.defaults().fillX().padBottom(2f);
        rebuildRows();

        cont.table(s -> {
            TextField search = new TextField(filterText);
            String hint = "搜索曲名";
            try { String v = Core.bundle.get("musicplayer.search"); if (v != null && !v.contains("??")) hint = v; } catch (Exception ignored) {}
            search.setMessageText(hint);
            search.changed(() -> { filterText = search.getText(); rebuildRows(); });
            s.image(Icon.zoom).size(14f).padRight(4f);
            s.add(search).growX().height(32f);
            if (!filterText.isEmpty()) {
                s.button(Icon.cancel, Styles.cleari, () -> { filterText = ""; rebuild(); }).size(28f).padLeft(4f);
            }
        }).growX().padTop(4f).padBottom(2f).row();
        final arc.scene.ui.Label countLbl = new arc.scene.ui.Label("", Styles.outlineLabel);
        countLbl.setColor(Color.lightGray);
        cont.table(h -> {
            h.image(Icon.book).size(12f).padRight(4f);
            h.add(countLbl).left();
            h.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("共 " + MusicPlayer.tracks().size + " 首")));
        }).left().padBottom(2f).row();
        // 计数标签：只在「总数/筛选条件/可见数」变化时才重算+setText。
        // 原实现每帧都遍历整库并做 toLowerCase() 字符串分配（列表长时是明显的每帧开销）。
        final int[] lastCountKey = {-1};
        countLbl.update(() -> {
            int total = MusicPlayer.tracks().size;
            String ft = filterText == null ? "" : filterText.trim().toLowerCase();
            int key = (total * 31 + ft.hashCode()) * 31 + (filterAlbum == null ? 0 : filterAlbum.hashCode());
            if (key == lastCountKey[0]) return; // 输入没变：连可见数都不必重算（可见数只由这三者决定）
            lastCountKey[0] = key;
            int visible = 0;
            for (MusicTrack t : MusicPlayer.tracks()) {
                if (filterAlbum != null && !isInAlbum(filterAlbum, t.cacheHash)) continue;
                if (!ft.isEmpty() && (t.name == null || !t.name.toLowerCase().contains(ft))) continue;
                visible++;
            }
            String txt = ft.isEmpty() && filterAlbum == null ? "[gray]曲目列表 (" + total + ")[]"
                    : "[gray]曲目列表 (" + visible + "/" + total + ")[]";
            if (!txt.equals(countLbl.getText().toString())) countLbl.setText(txt);
        });
        ScrollPane pane = new ScrollPane(trackTable, Styles.defaultPane);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        // 曲目列表吃满剩余高度 → 弹窗一直撑到接近屏幕底部（用户要求）。
        // 关键：**全程用像素算**，最后再换算成 cell 单位——因为 Cell.height(x) 内部还会乘一次 Scl.scl()，
        // 混用单位会把列表设成远大于屏幕的高度（实测：弹窗比屏幕还高、看起来"更高了却仍没到底"）。
        // 可用高度 = 屏幕高 - 其它行实际占用 - 上下留白；其它行的实际高度不受列表高度影响，所以这个反推是稳的。
        final float[] listPx = {Math.max(210f, Core.graphics.getHeight() * 0.30f)};
        final arc.scene.ui.layout.Cell<?> paneCell = cont.add(pane).growX()
                .height(listPx[0] / Scl.scl(1f)).padTop(2f);
        paneCell.row();
        update(() -> {
            try {
                if (cont.getHeight() <= 0f || pane.getHeight() <= 0f) return; // 首帧还没布局
                float screenPx = Core.graphics.getHeight();
                // 其它行占用 = cont 里除列表外**各子元素的实际高度之和**。
                // 不能用 cont.getHeight()-pane.getHeight()：实测 ScrollPane.getHeight() 并不是它格子的高度
                // （cell=616px 时它报 150px），那样算出来的 other 会被虚高 1800+px，列表反被压到最小。
                float otherPx = 0f;
                StringBuilder rows = new StringBuilder();
                for (arc.scene.Element e : cont.getChildren()) {
                    if (e == pane) continue;
                    otherPx += e.getHeight();
                    rows.append(' ').append(e.getClass().getSimpleName()).append('=').append((int) e.getHeight());
                }
                if (otherPx < 100f) return; // 各行为 0：还没完成首次布局，别套用错误值（否则弹窗会先跳一下）
                // 窗口自身开销 = 标题栏 + 底部按钮行 + 边距：它们不在 cont 里，必须一起算，
                // 否则总高会超过屏幕（实测 contH 2108 > 屏幕 2054，列表底部被切掉）。
                float overhead = Math.max(0f, getHeight() - cont.getHeight());
                // 末尾再留出**一行曲目**的空隙（用户要求「留一行空隙」）：一行 = 44 cell 单位 × Scl
                float oneRowPx = 44f * Scl.scl(1f);
                // 上下安全留白：弹窗是居中的，所以这里留 180px 总量 ≈ 上下各 90px——
                // 之前只留 32px 时整窗顶到屏幕边，标题「音乐播放器」被顶部 HUD/屏幕边缘挡住（用户反馈）。
                float safePx = 180f;
                float avail = screenPx - overhead - otherPx - safePx - oneRowPx;
                float targetPx = Math.max(210f, avail);
                if (Math.abs(targetPx - listPx[0]) > 6f) {                    // 变化不大就不动，避免每帧重排
                    MusicBar.diag("list height: screen=" + (int) screenPx + " contH=" + (int) cont.getHeight()
                            + " overhead=" + (int) overhead + " other=" + (int) otherPx
                            + " -> target=" + (int) targetPx + " (cell=" + (int) (targetPx / Scl.scl(1f)) + ")"
                            + " rows:" + rows);
                    listPx[0] = targetPx;
                    paneCell.height(targetPx / Scl.scl(1f));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private String nowPlayingLabel() {
        MusicTrack t = MusicPlayer.currentTrack();
        if (t == null) {
            try { String v = Core.bundle.get("musicplayer.none"); return v != null && !v.contains("??") ? v : "none"; } catch (Exception e) { return "none"; }
        }
        return t.name == null ? "" : t.name.replace("[", "[[").replace("]", "]]");
    }

    /** 秒 → m:ss 格式 */
    private static String formatTime(float sec, float len) {
        return fmt(sec) + " / " + (len > 0f ? fmt(len) : "--:--");
    }

    private static String fmt(float sec) {
        if (Float.isNaN(sec) || Float.isInfinite(sec) || sec < 0f) sec = 0f;
        int total = (int) sec;
        return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
    }

    private static String fmt1(float sec) {
        if (Float.isNaN(sec) || Float.isInfinite(sec) || sec < 0f) sec = 0f;
        int m = (int) (sec / 60);
        float s = sec % 60f;
        return m + ":" + (s < 10f ? "0" : "") + String.format(java.util.Locale.US, "%.1f", s);
    }

    /** A-B 区间状态文字：未设置显示「未设置」；两点已设但间距未达 hasAb 阈值时显示「过短未生效」；正常显示区间与开关态（带1位小数便于精确区分，缺键回退） */
    private static String abStatusText() {
        try {
            if (MusicPlayer.hasAb()) {
                float lo = Math.min(MusicPlayer.abA(), MusicPlayer.abB());
                float hi = Math.max(MusicPlayer.abA(), MusicPlayer.abB());
                String on = Core.bundle.get("musicplayer.abOn");
                return (on.contains("??") ? "A-B" : on) + "  " + fmt1(lo) + " - " + fmt1(hi);
            }
            if (MusicPlayer.abA() >= 0f && MusicPlayer.abB() >= 0f) {
                String s = Core.bundle.get("musicplayer.abTooShort");
                return s.contains("??") ? "too short" : s;
            }
            String u = Core.bundle.get("musicplayer.abUnset");
            return u.contains("??") ? "unset" : u;
        } catch (Exception e) { return MusicPlayer.hasAb() ? "A-B" : "unset"; }
    }

    // 倍速对数映射（1/16–16x）：speed = (1/16) * 256^cursor，256 = 16/(1/16)
    private static final float LOG_MIN = MusicPlayer.MIN_SPEED;
    private static final float LOG_RATIO = 16f / MusicPlayer.MIN_SPEED;

    private static float speedToCursor(float speed) {
        speed = Math.max(LOG_MIN, Math.min(16f, speed));
        return (float) (Math.log(speed / LOG_MIN) / Math.log(LOG_RATIO));
    }

    private static float cursorToSpeed(float cursor) {
        cursor = Math.max(0f, Math.min(1f, cursor));
        return (float) (LOG_MIN * Math.pow(LOG_RATIO, cursor));
    }

    private static String formatSpeed(float s) {
        if (Math.abs(s - 1f) < 0.001f) return "1x";
        if (Math.abs(s - Math.round(s)) < 0.001f) return Math.round(s) + "x";
        return String.format(java.util.Locale.US, "%.2fx", s);
    }

    private void togglePlay() {
        // 起播阶段（按钮已画成暂停）也走 pause：否则这一下反而又发起一次 resume，与图标语义相反
        if (MusicPlayer.isPlaying() || MusicPlayer.isStarting()) {
            MusicPlayer.pause();
        } else {
            MusicPlayer.resume();
        }
        // 不再 rebuild()：主按钮图标/「现在播放」面板都有每帧 update 同步，
        // 整窗重建既浪费又会造成按钮短暂重排（观感像闪一下）。
    }

    private void rebuildRows() {
        if (trackTable == null) return;
        trackTable.clearChildren();
        Seq<MusicTrack> tracks = MusicPlayer.tracks();
        if (tracks.size == 0) {
            trackTable.add(Core.bundle.get("musicplayer.empty")).color(Color.lightGray).pad(10f);
            return;
        }
        int current = MusicPlayer.currentIndex();
        // 过滤：仅显示当前激活专辑内的曲目（filterAlbum != null 时）
        int count = 0;
        String ft = filterText == null ? "" : filterText.trim().toLowerCase();
        for (int i = 0; i < tracks.size; i++) {
            MusicTrack t = tracks.get(i);
            if (filterAlbum != null && !isInAlbum(filterAlbum, t.cacheHash)) continue;
            if (!ft.isEmpty() && (t.name == null || !t.name.toLowerCase().contains(ft))) continue;
            count++;
            int idx = i;
            boolean isCurrent = current == i;
            Table row = new Table();
            if (isCurrent) row.background(Styles.grayPanel);
            else if (count % 2 == 0) row.background(Styles.grayPanelDark);
            row.defaults().pad(2f);
            // 曲名（滚动循环显示 + 固定宽）→ 长名自动滚动、不撑宽按钮破坏对齐与行结构
            String safeName = t.name == null ? "" : t.name.replace("[", "[[").replace("]", "]]");
            MusicBar.MarqueeLabel name = new MusicBar.MarqueeLabel(
                    (isCurrent ? "[accent]> " : "") + safeName, Styles.outlineLabel);
            name.setColor(isCurrent ? Pal.accent : Color.white);
            // 必须封顶：maxPref=0 时 MarqueeLabel.getPrefWidth() 会把整段曲名宽度泄漏进行宽，
            name.clicked(() -> { MusicPlayer.play(idx); rebuildRows(); });
            row.add(name).height(44f).growX().padRight(10f);
            // 类型标签独立固定宽列，右对齐 —— 与曲名分离，列宽稳定不致长名挤压
            row.add("[gray](" + Core.bundle.get(t.typeKey) + ")").
                    width(Scl.scl(116f)).right().color(Color.gray);
            // 音频信息：时长 / 文件大小分列固定宽右对齐，各行严格对齐（不再混排进同一 Label 造成参差）
            arc.scene.ui.Label timeLbl = new arc.scene.ui.Label(trackTimeText(t), Styles.outlineLabel);
            timeLbl.setColor(Color.gray);
            row.add(timeLbl).width(52f).right();
            arc.scene.ui.Label sizeLbl = new arc.scene.ui.Label(trackSizeText(t), Styles.outlineLabel);
            sizeLbl.setColor(Color.gray);
            row.add(sizeLbl).width(66f).right().padLeft(6f);
            // 专辑归属按钮：点击弹出「加入/移出专辑」菜单
            ImageButton albumBtn = new ImageButton(Icon.folder, Styles.cleari);
            albumBtn.resizeImage(Scl.scl(16f));
            albumBtn.addListener(new Tooltip(tbl -> tbl.background(Styles.black6).margin(4f).add("专辑")));
            albumBtn.clicked(() -> albumAssignDialog(idx));
            row.add(albumBtn).size(32f).padLeft(4f);
            ImageButton del = new ImageButton(Icon.trash, Styles.cleari);
            del.resizeImage(Scl.scl(18f));
            del.addListener(new Tooltip(tbl -> tbl.background(Styles.black6).margin(4f).add("删除")));
            del.clicked(() -> removeTrack(idx));
            row.add(del).size(36f).padLeft(4f);
            trackTable.add(row).growX().row();
        }
        if (count == 0) {
            String msg = !ft.isEmpty() ? "无匹配: " + ft : Core.bundle.get("musicplayer.albumEmpty");
            trackTable.add(msg).color(Color.lightGray).pad(10f);
        }
    }

    /** 判断某曲目 hash 是否属于指定专辑 */
    private static boolean isInAlbum(String albumName, String hash) {
        for (MusicPlayer.Album a : MusicPlayer.albums()) {
            if (albumName.equals(a.name) && a.hashes.contains(hash)) return true;
        }
        return false;
    }

    /** 专辑归属菜单：把当前曲目加入/移出某个专辑 */
    /**
     * 从曲目列表**多选**加入/移出指定专辑（用户需求：选好专辑后，从音乐播放器列表里挑曲）。
     * <p>
     * 交互：每首一行勾选框，进入时按「已在专辑里」预勾选；确定时一次性应用差异
     * （新勾的加入、取消勾的移出），因此这个弹窗同时充当「批量加」和「批量移出」。
     */
    private void albumPickTracksDialog(String albumName) {
        if (albumName == null) return;
        int ai = -1;
        Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
        for (int i = 0; i < albums.size; i++) {
            if (albumName.equals(albums.get(i).name)) { ai = i; break; }
        }
        if (ai < 0) return;
        final int albumIdx = ai;
        final Seq<MusicTrack> all = MusicPlayer.tracks();

        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.pickTracks"));
        Table list = new Table();
        list.top();
        list.defaults().pad(2f).left();
        final boolean[] checked = new boolean[all.size];
        final Seq<CheckBox> boxes = new Seq<>();
        for (int i = 0; i < all.size; i++) {
            MusicTrack t = all.get(i);
            checked[i] = isInAlbum(albumName, t.cacheHash);
            final int idx = i;
            CheckBox cb = new CheckBox(t.name == null ? "?" : t.name);
            cb.setChecked(checked[i]);
            cb.changed(() -> checked[idx] = cb.isChecked());
            boxes.add(cb);
            list.add(cb).growX().row();
        }
        if (all.size == 0) list.add(Core.bundle.get("musicplayer.empty")).color(Color.lightGray).pad(10f);

        dlg.cont.add(Core.bundle.get("musicplayer.importToAlbum") + ": [accent]"
                + albumName.replace("[", "[[").replace("]", "]]") + "[]").pad(8f).row();
        ScrollPane pane = new ScrollPane(list, Styles.defaultPane);
        pane.setFadeScrollBars(false);
        dlg.cont.add(pane).growX().height(320f).pad(4f).row();
        dlg.cont.table(row -> {
            row.defaults().height(BTN_H).pad(2f);
            row.add(textBtn(Core.bundle.get("musicplayer.selectAll"), () -> {
                for (int i = 0; i < checked.length; i++) { checked[i] = true; boxes.get(i).setChecked(true); }
            })).growX();
            row.add(textBtn(Core.bundle.get("musicplayer.selectNone"), () -> {
                for (int i = 0; i < checked.length; i++) { checked[i] = false; boxes.get(i).setChecked(false); }
            })).growX();
        }).growX().padTop(4f).row();
        // 确定：按勾选状态应用差异（只动真正变化的项，避免无谓的保存/洗牌）
        dlg.buttons.defaults().height(BTN_H).width(120f).pad(4f);
        dlg.buttons.add(textBtn(Core.bundle.get("musicplayer.confirm"), () -> {
            for (int i = 0; i < all.size; i++) {
                MusicTrack t = all.get(i);
                boolean was = isInAlbum(albumName, t.cacheHash);
                if (checked[i] && !was) MusicPlayer.addToAlbum(albumIdx, i);
                else if (!checked[i] && was) MusicPlayer.removeFromAlbum(albumIdx, i);
            }
            dlg.hide();
            rebuildRows();
        }));
        dlg.buttons.add(textBtn(Core.bundle.get("universal-junction.cancel", "取消"), dlg::hide));
        dlg.closeOnBack();
        dlg.show();
    }

    private void albumAssignDialog(int trackIndex) {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addToAlbum"));
        Table list = new Table();
        list.top();
        MusicTrack t = MusicPlayer.trackAt(trackIndex);
        if (t == null) return;
        list.add(Core.bundle.get("musicplayer.playing") + ": [accent]" + t.name + "[]").left().pad(4f).row();
        Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
        if (albums.size == 0) {
            list.add(Core.bundle.get("musicplayer.noAlbum")).color(Color.lightGray).pad(6f).row();
        }
        for (int i = 0; i < albums.size; i++) {
            MusicPlayer.Album a = albums.get(i);
            final int ai = i;
            final String albumName = a.name;
            boolean inAlbum = a.hashes.contains(t.cacheHash);
            String label = (inAlbum ? "[accent]✓ [/]" : "  ") + a.name;
            TextButton b = textBtn(label, null);
            b.clicked(() -> {
                if (a.hashes.contains(t.cacheHash)) MusicPlayer.removeFromAlbum(ai, trackIndex);
                else MusicPlayer.addToAlbum(ai, trackIndex);
                dlg.hide();
                rebuildRows();
            });
            list.add(b).growX().height(BTN_H).pad(2f).row();
        }
        ScrollPane pane = new ScrollPane(list, Styles.defaultPane);
        dlg.cont.add(pane).grow().height(220f);
        TextButton ok = textBtn(Core.bundle.get("musicplayer.confirm"), dlg::hide);
        dlg.buttons.add(ok).width(120f).height(BTN_H);
        dlg.closeOnBack();
        dlg.show();
    }

    /** 删除当前正在筛选的专辑（仅 filterAlbum 非空时可删） */
    private void deleteCurrentAlbum() {
        if (filterAlbum == null) return;
        Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
        for (int i = 0; i < albums.size; i++) {
            if (filterAlbum.equals(albums.get(i).name)) {
                MusicPlayer.removeAlbum(i);
                break;
            }
        }
        // 若正在播放的曲目原来在删除专辑内且当前专辑作用域是它，会由 removeAlbum 复位 activeAlbum；
        // 这里把筛选也复位到全部
        filterAlbum = null;
        rebuild();
    }

    /** 新建专辑弹窗：输入名称创建 */
    private void newAlbumDialog() {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.newAlbum"));
        TextField field = new TextField();
        field.setMessageText(Core.bundle.get("musicplayer.albumName"));
        dlg.cont.add(field).growX().pad(10f).row();
        TextButton create = textBtn(Core.bundle.get("musicplayer.confirm"), null);
        create.clicked(() -> {
            String name = field.getText().trim();
            if (name.length() > MusicPlayer.MAX_ALBUM_NAME_LENGTH) name = name.substring(0, MusicPlayer.MAX_ALBUM_NAME_LENGTH);
            if (!name.isEmpty()) {
                MusicPlayer.addAlbum(name);
                filterAlbum = name;
                rebuild();
            }
            dlg.hide();
        });
        dlg.cont.add(create).width(120f).height(BTN_H);
        dlg.closeOnBack();
        dlg.show();
    }

    private void removeTrack(int idx) {
        MusicTrack t = MusicPlayer.trackAt(idx);
        String name = (t == null || t.name == null) ? "" : t.name; // 反序列化出来的 track 可能 name 为 null
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.confirm"));
        String q = "确定删除?";
        try { String v = Core.bundle.get("musicplayer.deleteConfirm"); if (v != null && !v.contains("??")) q = v; } catch (Exception ignored) {}
        dlg.cont.add(q + "\n[accent]" + (name.replace("[", "[[").replace("]", "]]")) + "[]").pad(10f).row();
        TextButton yes = textBtn(Core.bundle.get("musicplayer.confirm"), () -> {
            MusicPlayer.removeTrack(idx);
            rebuildRows();
            dlg.hide();
        });
        dlg.buttons.add(yes).width(100f).height(BTN_H);
        String cancel = "取消";
        try { String v = Core.bundle.get("universal-junction.cancel"); if (v != null && !v.contains("??")) cancel = v; } catch (Exception ignored) {}
        TextButton no = textBtn(cancel, dlg::hide);
        dlg.buttons.add(no).width(100f).height(BTN_H);
        dlg.closeOnBack();
        dlg.show();
    }

    /** 批量导入完成后的信息确认弹窗：逐条列出文件名/大小/时长；
     *  @param afterClose 关闭确认弹窗后执行（如本地导入重新弹出导入界面继续追加） */
    private void showImportResult(Seq<MusicTrack> added, Runnable afterClose) {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.importResult"));
        dlg.cont.table(list -> {
            list.top();
            list.defaults().pad(1f);
            for (MusicTrack t : added) {
                String info = trackInfoLabel(t);
                String safe = t.name == null ? "" : t.name.replace("[", "[[").replace("]", "]]");
                String line = "[accent]>[/] " + safe + (info.isEmpty() ? "" : "  [gray]" + info + "[]");
                list.add(new arc.scene.ui.Label(line, Styles.defaultLabel)).growX().left().row();
            }
        }).grow().pad(10f);
        TextButton ok = textBtn(Core.bundle.get("musicplayer.confirm"), () -> {
            dlg.hide();
            if (afterClose != null) afterClose.run();
        });
        dlg.buttons.add(ok).width(120f).height(BTN_H);
        dlg.closeOnBack();
        dlg.show();
    }

    /** 在「当前筛选专辑」下导入的新曲自动归入该专辑（导入完成即出现在当前列表） */
    private void autoAddToCurrentAlbum(MusicTrack t) {
        if (filterAlbum == null || t == null) return;
        MusicPlayer.addTrackHashToAlbum(filterAlbum, t.cacheHash);
    }

    private void showAddDialog() {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addTitle"));
        dlg.cont.table(t -> {
            addIconButton(t, Icon.book, "musicplayer.addInternal", () -> {
                dlg.hide();
                internalPickTracksDialog(); // 与「从列表选取曲目」同一形式（多选）
            });
            addIconButton(t, Icon.link, "musicplayer.addUrl", () -> {
                dlg.hide();
                showSourceInput(MusicTrack.URL);
            });
            addIconButton(t, Icon.file, "musicplayer.addLocal", () -> {
                dlg.hide();
                showSourceInput(MusicTrack.LOCAL);
            });
            if (filterAlbum != null) {
                String safe = filterAlbum.replace("[", "[[").replace("]", "]]");
                // 已选中专辑时，多一个「从曲目列表多选」入口（用户需求：添加曲目时可从播放器列表里挑、可多选）
                addIconButton(t, Icon.list, "musicplayer.pickTracks", () -> {
                    dlg.hide();
                    albumPickTracksDialog(filterAlbum);
                });
                t.add("[gray]" + Core.bundle.get("musicplayer.importToAlbum") + ": [accent]" + safe + "[]")
                        .growX().padTop(6f);
            }
        }).pad(10f);
        dlg.closeOnBack();
        dlg.show();
    }

    /** 导入界面用的图标+文字按钮（本 arc 的 TextButton 无「图标+文案」构造器，用表内 Image+TextButton 拼装）。
     *  这里是「菜单项」而不是普通动作按钮，因此保持更高的行高（48），仅文字字号与其它按钮统一。 */
    private static void addIconButton(Table parent, arc.scene.style.Drawable icon, String bundleKey, Runnable action) {
        Table row = new Table();
        arc.scene.ui.Image img = new arc.scene.ui.Image(icon);
        img.setColor(Color.lightGray);
        row.add(img).size(24f).padRight(8f);
        row.add(textBtn(Core.bundle.get(bundleKey), action)).width(216f).height(48f);
        parent.add(row).pad(3f).row();
    }

    /**
     * 内置曲目的**多选**弹窗：与「从列表选取曲目」同一形式（勾选框 + 全选/全不选 + 确定）。
     * <p>
     * 内置曲目本来就已经在曲库里（ensureInternalTracks），所以「添加」的语义是：
     * <ul>
     *   <li>已选中专辑 → 把勾选的内置曲目批量加入该专辑；</li>
     *   <li>没选专辑 → 播放第一个勾选项（保留原来「点一首就播」的用途）。</li>
     * </ul>
     */
    private void internalPickTracksDialog() {
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addInternal"));
        String[] keysRaw = MusicPlayer.internalKeys();
        final String[] keys = keysRaw == null ? new String[0] : keysRaw; // 必须 effectively final：确定按钮的 lambda 会捕获它
        Table list = new Table();
        list.top();
        list.defaults().pad(2f).left();
        final boolean[] checked = new boolean[keys.length];
        final Seq<CheckBox> boxes = new Seq<>();
        for (int i = 0; i < keys.length; i++) {
            final String k = keys[i];
            String label;
            try { String v = Core.bundle.get("music." + k); label = (v != null && !v.contains("??")) ? v : k; } catch (Exception e) { label = k; }
            final int idx = i;
            MusicTrack t = MusicPlayer.trackByHash("int-" + k);
            checked[i] = filterAlbum != null && t != null && isInAlbum(filterAlbum, t.cacheHash);
            CheckBox cb = new CheckBox(label);
            cb.setChecked(checked[i]);
            cb.changed(() -> checked[idx] = cb.isChecked());
            boxes.add(cb);
            list.add(cb).growX().row();
        }
        if (filterAlbum != null) {
            dlg.cont.add(Core.bundle.get("musicplayer.importToAlbum") + ": [accent]"
                    + filterAlbum.replace("[", "[[").replace("]", "]]") + "[]").pad(8f).row();
        }
        ScrollPane pane = new ScrollPane(list, Styles.defaultPane);
        pane.setFadeScrollBars(false);
        dlg.cont.add(pane).growX().height(320f).pad(4f).row();
        dlg.cont.table(row -> {
            row.defaults().height(BTN_H).pad(2f);
            row.add(textBtn(Core.bundle.get("musicplayer.selectAll"), () -> {
                for (int i = 0; i < checked.length; i++) { checked[i] = true; boxes.get(i).setChecked(true); }
            })).growX();
            row.add(textBtn(Core.bundle.get("musicplayer.selectNone"), () -> {
                for (int i = 0; i < checked.length; i++) { checked[i] = false; boxes.get(i).setChecked(false); }
            })).growX();
        }).growX().padTop(4f).row();
        dlg.buttons.defaults().height(BTN_H).width(120f).pad(4f);
        dlg.buttons.add(textBtn(Core.bundle.get("musicplayer.confirm"), () -> {
            int firstIdx = -1;
            for (int i = 0; i < keys.length; i++) {
                MusicTrack t = MusicPlayer.trackByHash("int-" + keys[i]);
                if (t == null) continue;
                if (filterAlbum != null) {
                    // **两个方向都要处理**：勾选 → 加入专辑；取消勾选 → 从专辑移除。
                    // 上一版只处理勾选项（if (!checked[i]) continue），导致「取消勾选后不会从专辑删除」。
                    boolean was = isInAlbum(filterAlbum, t.cacheHash);
                    if (checked[i] && !was) MusicPlayer.addTrackHashToAlbum(filterAlbum, t.cacheHash);
                    else if (!checked[i] && was) MusicPlayer.removeTrackHashFromAlbum(filterAlbum, t.cacheHash);
                }
                if (checked[i] && firstIdx < 0) firstIdx = MusicPlayer.tracks().indexOf(t);
            }
            dlg.hide();
            if (filterAlbum != null) rebuildRows();
            else if (firstIdx >= 0) MusicPlayer.play(firstIdx);
        }));
        dlg.buttons.add(textBtn(Core.bundle.get("universal-junction.cancel", "取消"), dlg::hide));
        dlg.closeOnBack();
        dlg.show();
    }



    private void showSourceInput(int type) {
        if (type == MusicTrack.LOCAL) {
            // 本地文件：唤起系统文件选择框（支持多选；导入完成后弹出信息确认）
            mindustry.ui.FileChooser.FileChooserParams params = new mindustry.ui.FileChooser.FileChooserParams();
            params.open = true;
            params.extensions = new String[]{"ogg", "mp3", "wav", "flac", "m4a", "wma", "aac", "opus"};
            params.title = Core.bundle.get("musicplayer.addLocal");
            params.submitMulti(files -> {
                // 桌面 Platform 用后台 daemon 线程回调，UI 更新与 tracks 改动必须切回主线程
                final arc.struct.Seq<arc.files.Fi> picked = new arc.struct.Seq<>(files);
                Core.app.post(() -> {
                    Seq<MusicTrack> added = new Seq<>();
                    for (arc.files.Fi f : picked) {
                        String src = f.absolutePath();
                        if (src == null || src.isEmpty()) continue;
                        MusicTrack t = MusicPlayer.addTrack(MusicTrack.LOCAL, src, f.name());
                        if (t != null) {
                            added.add(t);
                            autoAddToCurrentAlbum(t);
                        }
                    }
                    if (added.size > 0) {
                        this.rebuild();
                        // 本地导入不退出导入界面：确认结果后重新弹出导入界面，便于继续追加
                        showImportResult(added, this::showAddDialog);
                    } else {
                        showAddDialog();
                    }
                });
            });
            return;
        }
        // URL：文本输入框
        BaseDialog dlg = new BaseDialog(Core.bundle.get("musicplayer.addUrl"));
        TextField field = new TextField();
        field.setMessageText(Core.bundle.get("musicplayer.placeholderUrl"));
        dlg.cont.add(field).growX().pad(10f).row();
        dlg.cont.add(Core.bundle.get("musicplayer.urlHint"));
        dlg.cont.row();
        // 无效输入提示（初始隐藏，输入不合法时显示）
        final arc.scene.ui.Label err = new arc.scene.ui.Label(Core.bundle.get("musicplayer.invalid"), Styles.defaultLabel);
        err.setColor(Color.scarlet);
        err.visible = false;
        dlg.cont.add(err).growX().padTop(2f).row();
        // 当前筛选专辑提示：导入后自动归入
        if (filterAlbum != null) {
            String safe2 = filterAlbum.replace("[", "[[").replace("]", "]]");
            dlg.cont.add("[gray]" + Core.bundle.get("musicplayer.importToAlbum") + ": [accent]" + safe2 + "[]")
                    .growX().padTop(2f).row();
        }
        TextButton okBtn = textBtn(Core.bundle.get("musicplayer.confirm"), null);
        okBtn.clicked(() -> {
            String src = field.getText().trim();
            if (src.isEmpty()) { dlg.hide(); return; }
            String name = null;
            int slash = Math.max(src.lastIndexOf('/'), src.lastIndexOf('\\'));
            if (slash >= 0 && slash < src.length() - 1) {
                name = src.substring(slash + 1);
                int q = name.indexOf('?'); if (q >= 0) name = name.substring(0, q);
                int h = name.indexOf('#'); if (h >= 0) name = name.substring(0, h);
                if (name.length() > MusicPlayer.MAX_TRACK_NAME_LENGTH) name = name.substring(0, MusicPlayer.MAX_TRACK_NAME_LENGTH);
                if (name.isEmpty()) name = null;
            }
            MusicTrack t = MusicPlayer.addTrack(MusicTrack.URL, src, name);
            if (t == null) {
                // 非法来源（无匹配扩展名等）：提示并保持弹窗，不静默关闭
                err.visible = true;
                return;
            }
            autoAddToCurrentAlbum(t);
            dlg.hide();
            rebuild();
        });
        dlg.cont.add(okBtn).width(120f).height(BTN_H);
        dlg.closeOnBack();
        dlg.show();
    }

    /** 循环模式按钮文案（6 种，缺键回退） */
    private static String loopModeText() {
        try { String v = Core.bundle.get("musicplayer.loopmode." + MusicPlayer.loopMode()); return v != null && !v.contains("??") ? v : ("loop" + MusicPlayer.loopMode()); } catch (Exception e) { return "loop" + MusicPlayer.loopMode(); }
    }

    // 音量滑杆映射（0–1000%）：100% 位于滑杆正中间，两侧刻度不同。
    // 滑杆值 x（0–1000）：
    //   左半 x∈[0,500] → 百分比 0–100%（压缩到左半）
    //   右半 x∈[500,1000] → 百分比 100–1000%（铺满右半）
    // 百分比 → 内部增益 g：
    //   0–100%：g=(P/100)^2  （0→0，100%→1，二次曲线低端更细腻）
    //   100–1000%：g=10^((P-100)/900)（100%→1，1000%→10，即 +20dB，真的放大千倍音量的 10x）
    // 即默认 100% 落在滑杆中点（x=500）。
    private static float volToPct(float x) {
        if (x <= 500f) return x * 100f / 500f;         // 0..100
        return 100f + (x - 500f) / 500f * 900f;        // 100..1000
    }

    private static float pctToVol(float pct) {
        if (pct <= 100f) return pct * 500f / 100f;     // 0..500
        return 500f + (pct - 100f) / 900f * 500f;      // 500..1000
    }

    private static float pctToGain(float pct) {
        if (pct <= 0f) return 0f;
        if (pct <= 100f) return (float) java.lang.Math.pow(pct / 100.0, 2.0);
        return (float) java.lang.Math.pow(10.0, (pct - 100.0) / 900.0);
    }

    private static float gainToPct(float g) {
        if (g <= 0f) return 0f;
        if (g <= 1f) return (float) (100.0 * java.lang.Math.sqrt(g));
        return (float) (100.0 + 900.0 * java.lang.Math.log10(g));
    }

    private static String pctText(float pct) {
        return Math.round(pct) + "%";
    }

    /** 曲目时长文本（未知显示占位符，避免各行宽度闪跳）。当前播放曲若正播且声源长度已知，优先用 trackLength()（含声源实时长度），
     *  避免超限大文件（>64MB 探针受限）在列表里一直显示 --:--，而悬浮条已能显示真实时长的不一致。
     *  外部音频下载/分块接收中时显示“下载中/接收中”而非 --:--，便于区分“未知”与“进行中”。 */
    private static String trackTimeText(MusicTrack t) {
        if (t != null) {
            if (MusicNetwork.isDownloading(t.cacheHash)) return "下载中";
            if (MusicNetwork.isReceiving(t.cacheHash)) {
                int p = MusicNetwork.receiveProgress(t.cacheHash);
                return p >= 0 ? "接收中" + p + "%" : "接收中";
            }
        }
        float len = -1f;
        MusicTrack cur = MusicPlayer.currentTrack();
        if (cur != null && t != null && cur.cacheHash.equals(t.cacheHash)) {
            float curLen = MusicPlayer.trackLength();
            if (curLen > 0f) len = curLen;
        }
        // 列表用非阻塞取值：未缓存则后台探测（绝不在此处做文件 I/O / 整文件拷贝）
        if (len <= 0f) len = MusicPlayer.trackLengthCached(t);
        if (len <= 0f) return "--:--";
        int total = (int) len;
        return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
    }

    /** 曲目文件大小文本（未知显示占位符），接收中时同步显示进度 */
    private static String trackSizeText(MusicTrack t) {
        if (t != null && MusicNetwork.isReceiving(t.cacheHash)) {
            int p = MusicNetwork.receiveProgress(t.cacheHash);
            return p >= 0 ? p + "%" : "--";
        }
        long size = MusicPlayer.trackSizeOf(t);
        if (size <= 0) return "--";
        if (size > 1048576) return String.format(java.util.Locale.US, "%.1fM", size / 1048576.0);
        if (size > 1024) return String.format(java.util.Locale.US, "%.0fK", size / 1024.0);
        return size + "B";
    }

    /** 曲目信息标签：时长 + 文件大小（导入结果对话框合并成一行展示用） */
    private static String trackInfoLabel(MusicTrack t) {
        String time = trackTimeText(t);
        String size = trackSizeText(t);
        return size.equals("--") ? time : time + "  " + size;
    }
}
