package silicon.ui;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.ScissorStack;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.util.Align;
import arc.util.Log;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.Drawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.TextButton;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.Scl;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import silicon.audio.MusicPlayer;
import silicon.audio.MusicTrack;

/**
 * 常驻悬浮音乐控制条（默认屏幕右下角，可拖动到任意位置）。
 * - 收起态：单一音符图标按钮（Icon.music），点击展开为完整控制条。
 * - 展开态：播放/暂停、曲名、专辑作用域、进度条、下一曲、收起按钮。
 * 由于触发顺序，scene 切换（进/出游戏、菜单）会重建 scene 并清空 root，
 * 因此每帧检查条是否仍在当前 scene，脱落后自动重建。全局仅一个实例。
 * 位置在拖动后持久化到设置，跨会话记忆；按钮均用 iconfont / bundle 文字，避免 unicode 符号缺字。
 */
public class MusicBar {
    private static final String CFG_X = "musicbar.pos.x";
    /** 左上角锚点的「上边缘」坐标（阶段3 改为以左上角为基准；旧 CFG_Y_LEGACY 是左下角，仅用于一次性迁移） */
    private static final String CFG_TOP = "musicbar.pos.top";
    private static final String CFG_Y_LEGACY = "musicbar.pos.y";
    private static final String CFG_COLLAPSED = "musicbar.collapsed";
    /** 面板内边距（未缩放单位，与 Table.margin 一致） */
    private static final float BAR_MARGIN = 4f;
    /** 收起态按钮边长（Scl 单位）——面板尺寸必须 = 按钮 + 两侧 margin，否则按钮会溢出面板背景 */
    private static final float COLLAPSED_BTN = 40f;
    /** 文字按钮统一高度（Scl 单位）：与弹窗内的 BTN_H 一致，避免同一套 UI 里按钮高矮不一 */
    private static final float TEXT_BTN_H = 34f;
    /** 展开态面板宽度（Scl 单位）：曲名/进度行以 growX 铺满，长曲名在固定宽内滚动裁剪 */
    private static final float EXPANDED_WIDTH = 600f;
    /** 默认位置：贴左边缘（Scl 单位） */
    private static final float DEFAULT_LEFT = 8f;
    /** 默认位置：屏幕高度比例（≈ 用户当前所在位置，见 settings: pos.y=1147 于 1440 高、uiscale 150%） */
    private static final float DEFAULT_TOP_FRAC = 0.85f;
    private static Table bar;
    private static boolean collapsed = true;
    /** 当前展开/收起态播放按钮的引用：供 MusicPlayer 每帧推送刷新（元素自身 update 不可靠，见 syncNow） */
    private static ImageButton playBtn;
    private static ImageButton collapsedBtn;

    private MusicBar() {}

    public static void init() {
        collapsed = Core.settings.getBool(CFG_COLLAPSED, true);
        Events.run(EventType.Trigger.update, () -> {
            if (Core.scene == null || Core.scene.root == null) {
                bar = null;
                return;
            }
            if (bar != null && !bar.isDescendantOf(Core.scene.root)) {
                // scene 未真正变化但条被其它 UI 从 root 摘除：移除遗留控件并置空，避免孤儿 → 重复新建出新实例
                bar.remove();
                bar = null;
            }
            // 仅在游戏中且有玩家实体时显示（避免主菜单 player==null 时误播 NPE）
            if (!mindustry.Vars.state.isGame() || mindustry.Vars.player == null || !MusicPlayer.isEnabled()) {
                if (bar != null) {
                    // 记录被挡掉的原因：总开关关掉时悬浮条会整体消失（音乐却照常播放），
                    // 用户只会看到「条不见了」，非常难排查——写进诊断文件（含是否因为在游戏中/有没有玩家）
                    diag("bar hidden: isGame=" + mindustry.Vars.state.isGame()
                            + " player=" + (mindustry.Vars.player != null)
                            + " enabled=" + MusicPlayer.isEnabled());
                    bar.remove();
                    bar = null;
                }
                return;
            }
            if (bar == null) {
                build();
            }
        });
    }

    private static void build() {
        bar = new Table();
        // 悬浮条背景：半透明黑（black6 = 60% 黑），既保证按钮/文字对比度、又能透出后面的游戏画面
        // （grayPanel 是纯色 region，会精确铺满元素范围，所以「背景不能完全覆盖」只可能是元素被设得比内容小
        //  —— 两处尺寸都按下文修正过）
        bar.background(Styles.black6);
        bar.margin(BAR_MARGIN);
        // 展开态宽度（屏幕自适应，在下面 expanded 分支里按屏幕算）：声明在分支外，供 setSize 使用
        final float[] barW = {Scl.scl(EXPANDED_WIDTH)};

        if (collapsed) {
            // 收起态：播放中显示暂停、暂停中显示播放，颜色随状态高亮，悬停显示曲名
            ImageButton btn = new ImageButton(MusicPlayer.isPlaying() ? Icon.pause : Icon.play, playStyle(Styles.cleari));
            btn.resizeImage(Scl.scl(26f));
            collapsedBtn = btn; // 供 MusicPlayer 每帧推送刷新（见 syncNow）
            btn.update(() -> {
                boolean p = MusicPlayer.isPlaying();
                boolean starting = !p && MusicPlayer.isStarting();
                btn.getImage().setDrawable(p || starting ? Icon.pause : Icon.play);
                btn.getImage().setColor(p ? Pal.accent : (starting ? Color.lightGray : Color.white));
            });
            btn.addListener(new Tooltip(t -> {
                MusicTrack curTrack = MusicPlayer.currentTrack();
                String cur = (curTrack == null || curTrack.name == null) ? "none" : curTrack.name;
                t.background(Styles.black6).margin(4f).add(cur.replace("[", "[[").replace("]", "]]"));
            }));
            bar.add(btn).size(COLLAPSED_BTN);
            makeDraggable(btn, () -> {
                collapsed = false;
                Core.settings.put(CFG_COLLAPSED, false);
                detach();
            });
            bar.pack();
            // 尺寸交给 pack() 决定：Cell.size 与 Table.margin 内部各自乘一次 Scl，手算面板边长容易单位写错，
            // 而且这里原本 setSize 之后又在展开/收起共用段被 pack() 覆盖，属于死代码。
        } else {
            // 展开态：整条 = 单列三行（按钮排 / 曲名+时间 / 进度条）。
            // 关键：每行都必须自己铺满整条宽度。旧实现把 11 个按钮直接排成 11 列，
            // 下面两行用 colspan(11) 去跨这些列，于是行宽被「按钮列宽之和」限制（≈516dp），
            // 而曲名行内容需要 ≈580dp（曲名 476 + 时间 96）→ 内容横向溢出面板右沿，
            // 表现为「背景不能完全覆盖」。改为按钮先装进子表，三行各自 growX，面板即精确覆盖内容。
            Table controls = new Table();
            ImageButton grip = new ImageButton(Icon.move, Styles.flati);
            grip.resizeImage(Scl.scl(20f));
            grip.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("拖动移动")) );
            controls.add(grip).size(32f).pad(1f);
            makeDraggable(grip);
            ImageButton prevBtn = new ImageButton(Icon.leftOpen, Styles.cleari);
            prevBtn.resizeImage(Scl.scl(18f));
            prevBtn.clicked(MusicPlayer::prev);
            prevBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("上一曲")));
            controls.add(prevBtn).size(32f).pad(1f);
            // 快退（相对 -10s）
            ImageButton rewindBtn = new ImageButton(Icon.leftSmall, Styles.cleari);
            rewindBtn.resizeImage(Scl.scl(18f));
            rewindBtn.clicked(() -> MusicPlayer.seekRelative(-10f));
            rewindBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("快退10秒")));
            controls.add(rewindBtn).size(32f).pad(1f);

            // 播放/暂停
            ImageButton play = new ImageButton(MusicPlayer.isPlaying() ? Icon.pause : Icon.play, playStyle(Styles.flati));
            play.resizeImage(Scl.scl(22f));
            playBtn = play; // 供 MusicPlayer 每帧推送刷新（见 syncNow）
            play.getImage().setColor(MusicPlayer.isPlaying() ? Pal.accent : Color.white);
            play.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("播放/暂停")));
            play.clicked(() -> {
                // 起播阶段（按钮画成暂停）点击也应当「暂停」，否则这一下反而又发起一次 resume
                if (MusicPlayer.isPlaying() || MusicPlayer.isStarting()) MusicPlayer.pause(); else MusicPlayer.resume();
                // 点击即同步图标/颜色（不依赖下一帧 update 才切换）
                applyPlayIcon(play);
            });
            // 每帧同步图标到当前播放态（兜底异步建源/暂停/停止路径，悬浮窗停止/开始按钮切换修复）
            playButtonFrameSync(play);
            controls.add(play).size(40f).pad(1f);

            // 快进（相对 +10s）
            ImageButton forwardBtn = new ImageButton(Icon.rightSmall, Styles.cleari);
            forwardBtn.resizeImage(Scl.scl(18f));
            forwardBtn.clicked(() -> MusicPlayer.seekRelative(10f));
            forwardBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("快进10秒")));
            controls.add(forwardBtn).size(32f).pad(1f);
            ImageButton nextBtn = new ImageButton(Icon.rightOpen, Styles.cleari);
            nextBtn.resizeImage(Scl.scl(18f));
            nextBtn.clicked(MusicPlayer::next);
            nextBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("下一曲")));
            controls.add(nextBtn).size(32f).pad(1f);

            // 倍速快捷循环按钮（覆盖 1/16–16x 对数档的常用子集）：0.25 / 0.5 / 1 / 1.5 / 2 / 4 / 8；固定宽度完整显示
            final float[] speeds = {0.25f, 0.5f, 1f, 1.5f, 2f, 4f, 8f};
            TextButton speedBtn = new TextButton(speedLabel(), Styles.flatBordert);
            speedBtn.getLabel().setWrap(false);
            speedBtn.getLabel().setEllipsis(false);
            speedBtn.setColor(Pal.accent);
            speedBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("点击切换倍速")));
            final String[] lastSpeed = {speedLabel()};
            speedBtn.update(() -> {
                String lbl = speedLabel();
                if (!lastSpeed[0].equals(lbl)) { lastSpeed[0] = lbl; speedBtn.setText(lbl); }
            });
            speedBtn.clicked(() -> {
                float cur = MusicPlayer.speed();
                float next = speeds[0];
                for (float s : speeds) if (s > cur + 0.01f) { next = s; break; }
                MusicPlayer.setSpeed(next);
                speedBtn.setText(speedLabel());
            });
            controls.add(speedBtn).width(76f).height(TEXT_BTN_H).pad(1f);

            // 专辑作用域切换按钮：点按在「全部曲目」与各专辑间轮换；长按/双击由设置页管理
            TextButton albumBtn = new TextButton(albumScopeLabel(), Styles.flatBordert);
            albumBtn.getLabel().setWrap(false);
            albumBtn.getLabel().setEllipsis(true);
            albumBtn.setColor(Pal.accent);
            albumBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("点击切换专辑")));
            final String[] lastScope = {albumScopeLabel()};
            albumBtn.update(() -> {
                String lbl = albumScopeLabel();
                if (!lastScope[0].equals(lbl)) { lastScope[0] = lbl; albumBtn.setText(lbl); }
            });
            albumBtn.clicked(() -> {
                cycleAlbumScope();
                lastScope[0] = albumScopeLabel();
                albumBtn.setText(lastScope[0]);
            });
            controls.add(albumBtn).width(112f).height(TEXT_BTN_H).pad(1f);

            // 循环模式快捷按钮：点击在 6 种模式间循环。固定宽度（不等长文本切换不导致按钮忽大忽小/点小/换行），
            // 文案已改为等长的两字中文（关闭/列表/单曲/乱序/单停/随机），配合字号在固定格内完整显示不省略
            TextButton loopBtn = new TextButton(loopModeLabel(), Styles.flatBordert);
            loopBtn.getLabel().setWrap(false);
            loopBtn.getLabel().setEllipsis(false);
            loopBtn.setColor(Pal.accent);
            loopBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("点击切换循环模式")));
            final String[] lastLoop = {loopModeLabel()};
            loopBtn.update(() -> {
                String lbl = loopModeLabel();
                if (!lastLoop[0].equals(lbl)) { lastLoop[0] = lbl; loopBtn.setText(lbl); }
            });
            loopBtn.clicked(() -> {
                MusicPlayer.cycleLoopMode();
                lastLoop[0] = loopModeLabel();
                loopBtn.setText(lastLoop[0]);
            });
            controls.add(loopBtn).width(64f).height(TEXT_BTN_H).pad(1f);

            // 设置按钮：打开音乐播放器设置页
            ImageButton settingsBtn = new ImageButton(Icon.settings, Styles.cleari);
            settingsBtn.resizeImage(Scl.scl(18f));
            settingsBtn.clicked(MusicPlayerDialog::open);
            settingsBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("设置")));
            controls.add(settingsBtn).size(32f).pad(1f);

            // 收起（最小值化）
            ImageButton collapseBtn = new ImageButton(Icon.down, Styles.cleari);
            collapseBtn.resizeImage(Scl.scl(18f));
            collapseBtn.clicked(() -> { collapsed = true; Core.settings.put(CFG_COLLAPSED, true); detach(); });
            collapseBtn.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("收起")));
            controls.add(collapseBtn).size(32f).pad(1f);

            // 适配屏幕：展开条宽度 = 在「按钮排最小宽」与「600 Scl」之间取屏幕放得下的值。
            // 屏幕窄时按屏幕收窄（并让曲名可用宽跟着收窄），屏幕宽时保持 600 Scl 不变，
            // 这样大屏/小屏都不会出现「条比屏幕宽被顶到屏幕外」或「曲名行内容溢出面板」。
            barW[0] = Math.max(controls.getPrefWidth() + Scl.scl(BAR_MARGIN) * 2f,
                    Math.min(Scl.scl(EXPANDED_WIDTH), Core.graphics.getWidth() - Scl.scl(16f)));

            bar.add(controls).growX().height(40f).padBottom(2f);
            bar.row();
            // 曲名 + 当前/总时长：内嵌横向 Table，growX 铺满整条固定宽度 → 长曲名在条内滚动裁剪、不拉长整条
            Table infoRow = new Table();
            MarqueeLabel track = new MarqueeLabel(trackLabel(), Styles.outlineLabel);
            track.setColor(Color.white);
            // 曲名可用宽 = 条宽 - 右侧时长标签 - 边距：条宽随屏幕变化时这里同步（否则窄屏会把时长挤出面板）
            track.maxPref = Math.max(Scl.scl(120f), barW[0] - Scl.scl(96f) - Scl.scl(24f));
            track.clicked(() -> MusicPlayerDialog.open());
            final String[] lastTrack = {trackLabel()};
            track.update(() -> {
                String lbl = trackLabel();
                if (!lastTrack[0].equals(lbl)) { lastTrack[0] = lbl; track.setText(lbl); }
            });
            // 进度条需与时间标签共享拖动状态：创建时间标签前先创建滑杆实例，时间标签拖动时预览拖动位置
            final arc.scene.ui.Slider previewSlider = seekSlider();
            // 曲名 growX 占满剩余（MarqueeLabel 的 this.width 即剩余宽，超过才滚动）；右侧固定时长标签
            infoRow.add(track).growX().left();
            final arc.scene.ui.Label timeLbl = new arc.scene.ui.Label("0:00 / 0:00", Styles.outlineLabel);
            timeLbl.setColor(Color.white);
            // 字号与曲名标签/按钮一致（此前单独 0.75 显得比旁边小）
            timeLbl.update(() -> {
                float len = MusicPlayer.trackLength();
                float cur;
                if (previewSlider.isDragging() && len > 0f && len < 12f * 3600f) {
                    cur = previewSlider.getValue() * len;
                } else {
                    cur = MusicPlayer.currentTime();
                }
                String s = formatFloaterTime(cur, len);
                if (!s.equals(timeLbl.getText().toString())) timeLbl.setText(s);
            });
            infoRow.add(timeLbl).padLeft(8f).width(96f).right();
            // 行高给足（44f 彻底避免上半部被裁），配合 MarqueeLabel 垂直居中
            bar.add(infoRow).growX().pad(2f, 6f, 2f, 6f).left().height(44f);

            bar.row();
            // 进度条（独立一行，加高并上下留白，避免滑杆圆钮越界遮挡上方曲名/按钮文字）
            bar.add(previewSlider).growX().height(24f).pad(3f, 6f, 3f, 6f);
        }

        bar.pack();
        if (!collapsed) {
            // 展开态用上面算好的**屏幕自适应宽度**（三行都是 growX，宽度不需要问内容要）。
            // 关键：绝不使用 bar.getPrefWidth()——只要哪个子元素的 pref 被算成异常大值，
            // 条就会宽到超过屏幕 → clampBar 把 x 顶到最小值、条体大部分在屏幕外
            // （实测 settings 里 pos.x=3、pos.top=1720）。三行高度也都显式固定
            // （按钮排 40 / 曲名行 44 / 进度条 24），因此 pref 高度同样是确定的，不会再被撑高。
            bar.setSize(barW[0], bar.getPrefHeight());
        }
        // 兜底：无论布局算出多大的 pref，面板都不许超过屏幕。否则 clampBar 会把它推到 y=2，
        // 条内第一行（按钮排）就被推到屏幕上方看不见 —— 表现成「整个悬浮条不见了」（实测出现过：
        // settings 里 pos.x 被夹到最小值 3、pos.top 高达 1720，说明算出的尺寸远超屏幕）。
        float maxW = Core.graphics.getWidth() - Scl.scl(8f);
        float maxH = Core.graphics.getHeight() - Scl.scl(8f);
        boolean abnormal = bar.getWidth() > maxW || bar.getHeight() > maxH;
        if (abnormal) {
            bar.setSize(Math.min(bar.getWidth(), maxW), Math.min(bar.getHeight(), maxH));
        }
        applyStoredPosition();
        Core.scene.root.addChild(bar);
        // 诊断：悬浮条布局参数写入独立追加文件（last_log.txt 每次启动都被覆盖，会冲掉现场证据）
        diag("build collapsed=" + collapsed + " abnormal=" + abnormal
                + " pref=" + bar.getPrefWidth() + "x" + bar.getPrefHeight()
                + " size=" + bar.getWidth() + "x" + bar.getHeight()
                + " at=" + bar.x + "," + bar.y
                + " screen=" + Core.graphics.getWidth() + "x" + Core.graphics.getHeight()
                + " scl=" + Scl.scl(1f)
                + " cfgX=" + Core.settings.getFloat(CFG_X, -1f) + " cfgTop=" + Core.settings.getFloat(CFG_TOP, -1f));
        diagBar();
    }

    /** 诊断输出（包内可见，弹窗也在用）：同时进日志与 {@code silicon-musicbar.log}（追加，不会被下次启动覆盖） */
    static void diag(String msg) {
        Log.info("[Music] bar " + msg);
        try {
            Core.files.local("silicon-musicbar.log").writeString(msg + "\n", true);
        } catch (Throwable ignored) {
        }
    }

    /** 诊断：逐个子元素记录 pref/size（两层），用于定位是谁把悬浮条撑爆的 */
    private static void diagBar() {
        try {
            for (arc.scene.Element e : bar.getChildren()) {
                diag("  child " + e.getClass().getSimpleName()
                        + " pref=" + e.getPrefWidth() + "x" + e.getPrefHeight()
                        + " size=" + e.getWidth() + "x" + e.getHeight());
                if (e instanceof Table) {
                    for (arc.scene.Element c : ((Table) e).getChildren()) {
                        diag("    child " + c.getClass().getSimpleName()
                                + " pref=" + c.getPrefWidth() + "x" + c.getPrefHeight()
                                + " size=" + c.getWidth() + "x" + c.getHeight());
                    }
                }
            }
        } catch (Throwable t) {
            diag("  dump failed: " + t);
        }
    }

    /**
     * 位置以**左上角**为锚点：{@code bar.x} = 左边缘，存储的 top = 上边缘（场景 y 向上，故 bar.y = top - height）。
     * 这样收起/展开切换时左上角不动，按钮不会因为高度变化而“跳”。
     * <p>
     * 迁移：旧版本存的是左下角 y，检测到旧键就保留其垂直位置并把 x 贴到左边缘（一次性迁移后删除旧键）。
     * 首次使用（两个键都没有）则用默认值：贴左边缘 + 屏幕高度 {@link #DEFAULT_TOP_FRAC} 处。
     */
    private static void applyStoredPosition() {
        float left;
        float top;
        if (Core.settings.has(CFG_TOP)) {
            left = Core.settings.has(CFG_X) ? Core.settings.getFloat(CFG_X) : Scl.scl(DEFAULT_LEFT);
            top = Core.settings.getFloat(CFG_TOP);
        } else if (Core.settings.has(CFG_Y_LEGACY)) {
            left = Scl.scl(DEFAULT_LEFT);
            top = Core.settings.getFloat(CFG_Y_LEGACY) + bar.getHeight();
            Core.settings.remove(CFG_Y_LEGACY);
            Core.settings.put(CFG_X, left);
            Core.settings.put(CFG_TOP, top);
        } else {
            left = Scl.scl(DEFAULT_LEFT);
            top = Core.graphics.getHeight() * DEFAULT_TOP_FRAC;
        }
        bar.setPosition(left, top - bar.getHeight());
        clampBar();
    }



    /** 同步播放/暂停按钮图标颜色到当前 isPlaying 状态（悬浮窗停止/开始按钮切换修复）。
     *  另加「启动中」态：转码/解封装期间 isPlaying 仍为 false，若只按 isPlaying 画，用户点了播放
     *  按钮会看到图标毫无变化（长曲解码要几秒甚至几十秒）——此时画暂停图标并压暗，表示已受理、正在起播。
     *  <p>
     *  <b>必须改 style 而不是子 Image</b>：arc 的 {@code ImageButton.draw()} 每帧先调 {@code updateImage()}，
     *  它会把子 Image 的 drawable / color 用 {@code style.imageUp / imageUpColor / imageChecked…} 重新覆盖一遍
     *  （反编译确认 {@code Image.setDrawable} 就在 updateImage 内）。所以此前所有
     *  {@code btn.getImage().setDrawable(...)} 都在下一帧被打回去——这就是「按钮图标永远不变」的真正原因。
     *  这些按钮在创建时都带了自己的一份 style 副本（{@link #playStyle}），改它不会影响别的按钮。
     */
    public static void applyPlayIcon(ImageButton btn) {
        boolean p = MusicPlayer.isPlaying();
        boolean starting = !p && MusicPlayer.isStarting();
        arc.scene.style.Drawable icon = (p || starting) ? Icon.pause : Icon.play;
        arc.graphics.Color color = p ? Pal.accent : (starting ? Color.lightGray : Color.white);
        // 1) 该按钮自己的 style 副本（draw 每帧从它取值）
        arc.scene.ui.ImageButton.ImageButtonStyle st = btn.getStyle();
        if (st != null) {
            st.imageUp = icon;
            st.imageOver = icon;
            st.imageDown = icon;
            st.imageUpColor = color;
            st.imageOverColor = color;
            st.imageDownColor = color;
        }
        // 2) 兜底：子 Image 也设一遍（arc 若在别的路径直接画子元素时同样正确）
        if (btn.getImage() != null) {
            btn.getImage().setDrawable(icon);
            btn.getImage().setColor(color);
        }
    }

    /** 给播放/暂停类按钮做一份**独立的 style 副本**：图标要动态改，而 Styles.flati/cleari 是全局共享的，
     *  直接改会污染所有用它的按钮。 */
    static arc.scene.ui.ImageButton.ImageButtonStyle playStyle(arc.scene.ui.ImageButton.ImageButtonStyle base) {
        arc.scene.ui.ImageButton.ImageButtonStyle st = new arc.scene.ui.ImageButton.ImageButtonStyle(base);
        st.imageUp = Icon.play; // 初始占位，随后由 syncPlayButton 按真实状态覆盖
        st.imageOver = st.imageUp;
        st.imageDown = st.imageUp;
        st.imageChecked = null;
        return st;
    }

    /**
     * 由 {@link silicon.audio.MusicPlayer} 每帧直接推送刷新（不依赖元素自身的 update，见那边的注释）。
     * 收起/展开两个播放按钮都在这里统一刷，切换状态立刻反映到图标与颜色。
     */
    public static void syncNow() {
        if (playBtn != null) applyPlayIcon(playBtn);
        if (collapsedBtn != null) applyPlayIcon(collapsedBtn);
    }

    /** 每帧把播放态同步到按钮图标（相比只在变化时更新，能兜底一切异步建源/暂停/停止路径） */
    private static void playButtonFrameSync(ImageButton btn) {
        btn.update(() -> applyPlayIcon(btn));
    }

    /** 悬浮条/弹窗共用的可拖动进度条（内联 update+changed 监听，返回构造好的 Slider）；
     *  带 A-B 区间高亮带：进度条上半透明色带标出 [A,B] 区间，便于区间重复可视化。
     *  抖动修复：只在「非拖动」且「目标值与上次显示值差异超过阈值(±0.0005)」时才 setValue，
     *  避免每帧原地重设同一/近似值导致滑杆指针反复跳动。 */
    private static arc.scene.ui.Slider seekSlider() {
        arc.scene.ui.Slider seekBar = new AbSlider();
        seekBar.setDisabled(true);
        final boolean[] userSeek = {false};
        final float[] lastShown = {Float.NEGATIVE_INFINITY};
        seekBar.update(() -> {
            float len = MusicPlayer.trackLength();
            // 修复：未知长度(-1)、异常大值(>12h)或该声源 seek 已被判不可靠时禁用拖动，
            // 防止外部歌曲进度跳到超高值/触发「播完→自动跳下一首」
            boolean ok = len > 0f && len < 12f * 3600f && !MusicPlayer.isSeekUnreliable();
            seekBar.setDisabled(!ok);
            if (ok && !userSeek[0] && !seekBar.isDragging()) {
                float target = MusicPlayer.currentTime() / len;
                if (Math.abs(target - lastShown[0]) > 0.0005f) {
                    userSeek[0] = true;
                    seekBar.setValue(target);
                    lastShown[0] = target;
                    userSeek[0] = false;
                }
            }
        });
        seekBar.changed(() -> {
            float len = MusicPlayer.trackLength();
            if (!userSeek[0] && len > 0f && len < 12f * 3600f) {
                userSeek[0] = true;
                MusicPlayer.seek(seekBar.getValue() * len);
                lastShown[0] = seekBar.getValue();
                userSeek[0] = false;
            }
        });
        return seekBar;
    }

    /** 带 A-B 区间高亮带的进度滑块：在轨道上叠一层半透明色带标出区间范围（无区间时不画） */
    static class AbSlider extends arc.scene.ui.Slider {
        AbSlider() {
            super(0f, 1f, 0.001f, false);
        }

        @Override
        public void draw() {
            // 先画滑块本体（背景/旋钮），再叠 A-B 色带：反过来的话色带会被滑杆背景的九宫格盖住
            // （审查指出 sliderBack 中间透明、边框不透明，画在下面时会显得断断续续）。
            super.draw();
            if (MusicPlayer.hasAb()) {
                float len = MusicPlayer.trackLength();
                if (len > 0f) {
                    float lo = Math.min(MusicPlayer.abA(), MusicPlayer.abB()) / len;
                    float hi = Math.max(MusicPlayer.abA(), MusicPlayer.abB()) / len;
                    float w = this.x + this.width;
                    float x0 = this.x + Scl.scl(4f) + lo * (w - this.x - Scl.scl(8f));
                    float x1 = this.x + Scl.scl(4f) + hi * (w - this.x - Scl.scl(8f));
                    float mid = this.y + this.height / 2f;
                    float th = Math.max(Scl.scl(3f), Math.min(Scl.scl(6f), this.height * 0.5f));
                    Draw.color(Pal.accent, 0.45f);
                    Fill.crect(x0, mid - th / 2f, x1 - x0, th);
                    Draw.reset();
                }
            }
        }
    }

    /**
     * 长曲名「循环显示」滚动标签：文本超出自身宽度时自动横向滚动（无缝回绕），
     * 超出则剪裁到自身区域；文本长度未超出时不滚动、行为等同普通 Label。
     * - maxPref（0=不限）：限制 getPrefWidth 的上限，使 cell 占满可用宽度又不把 Table 撑长，
     *   且只有文本真的超过「可用宽度」才开始滚动（解决「长度足够却仍滚动」的问题）。
     * - 「有空间还循环」修复：滚动判定与周期一律用「真实文本宽」realWidth()（super.getPrefWidth()），
     *   而不是被 maxPref 截断后的 getPrefWidth()——否则 maxPref<真宽时周期按截断值算，
     *   副本重叠/截断造成「名字不全」，且长名仍触发滚动。
     * - 滚动间隙加大，进入副本与离开副本之间存在干净空白，避免相邻被遮挡。
     */
    static class MarqueeLabel extends arc.scene.ui.Label {
        public float maxPref = 0f;
        private float scroll = 0f;
        private String lastKey = "";
        /** 滚动回绕间隙（48dp 更易读） */
        private static final float GAP = 48f;

        MarqueeLabel(CharSequence text, LabelStyle style) {
            super(text, style);
            setWrap(false);
            setEllipsis(false);
            // 中心对齐：在固定的行高内文本居中显示
            setAlignment(Align.center);
        }

        @Override
        public float getPrefWidth() {
            float p = super.getPrefWidth();
            return maxPref > 0f ? Math.min(p, maxPref) : p;
        }

        /** 真实文本渲染宽（不经过 maxPref 截断），用于滚动周期/判定，保证长名完整循环 */
        private float realWidth() {
            return super.getPrefWidth();
        }

        private String textKey() {
            return this.text == null ? "" : this.text.toString();
        }

        @Override
        public void act(float delta) {
            super.act(delta);
            if (Float.isNaN(delta) || Float.isInfinite(delta)) return;
            delta = Math.min(delta, 0.1f);
            if (Float.isNaN(scroll) || Float.isInfinite(scroll)) scroll = 0f;
            String key = textKey();
            if (!lastKey.equals(key)) { lastKey = key; scroll = 0f; }
            float cw = this.width;
            float gw = Scl.scl(8f);
            float real = realWidth();
            if (cw > Scl.scl(1f) && real > cw + gw) {
                // 真实文本溢出 → 以「真实文本宽 + 间隙」为周期持续滚动，实现无缝全名循环
                scroll = (scroll + delta * Scl.scl(36f)) % (real + Scl.scl(GAP));
            } else {
                scroll = 0f;
            }
        }

        @Override
        public void draw() {
            float cw = this.width;
            float tw = realWidth();
            if (cw <= Scl.scl(1f) || tw <= cw + Scl.scl(8f) || scroll == 0f) {
                super.draw();
                return;
            }
            float ox = this.x;
            float period = tw + Scl.scl(GAP);
            float ph = scroll % period;
            Draw.flush();
            // 裁剪矩形必须是**舞台/屏幕坐标**：ScissorStack.push 直接把矩形喂给 glScissor（不乘矩阵），
            // 而 this.x/this.y 在弹窗里是「对话框局部坐标」（Dialog.show 会 setTransform(true)、
            // 子元素走 Draw.trans）——直接用局部坐标会让裁剪区域整体偏移对话框位置，
            // 结果是弹窗里的长曲名被裁到错误区域（悬浮条没有变换，所以那里看不出问题）。
            arc.math.geom.Vec2 stagePos = localToStageCoordinates(new arc.math.geom.Vec2(0f, 0f));
            boolean clipped = ScissorStack.push(new Rect(stagePos.x, stagePos.y, cw, this.height));
            try {
                this.x = ox - ph;
                super.draw();
                this.x = ox - ph + period;
                super.draw();
            } finally {
                this.x = ox;
                Draw.flush();
                if (clipped) ScissorStack.pop();
                Draw.flush();
            }
        }
    }

    /** 给元素挂拖动监听（用于收起态音符按钮：既能拖动也能单击展开）：
     *  - 用普通 addListener 并在 touchDown 返回 true 取得触摸焦点（而非 addCaptureListener），
     *    确保能可靠收到 touchDragged/touchUp，也不会干扰其它按钮的点击。
     *  - 拖动时移动整个 control bar（target 本身）。
     *  - 若最终未发生位移（单击），触发 onClick 回调（展开）。
     */
    private static void makeDraggable(Element target, Runnable onClick) {
        target.addListener(new InputListener() {
            float lastX, lastY;
            float startX, startY;
            boolean dragged;

            @Override
            public boolean touchDown(InputEvent e, float x, float y, int pointer, arc.input.KeyCode button) {
                lastX = e.stageX;
                lastY = e.stageY;
                startX = e.stageX;
                startY = e.stageY;
                dragged = false;
                return true;
            }

            @Override
            public void touchDragged(InputEvent e, float x, float y, int pointer) {
                float dx = e.stageX - lastX, dy = e.stageY - lastY;
                // 只有「累计位移超过阈值」才算拖动：原先任意 1px 抖动就置 dragged，导致 5px 点击阈值永远不成立、
                moveBar(dx, dy);
                lastX = e.stageX;
                lastY = e.stageY;
            }

            @Override
            public void touchUp(InputEvent e, float x, float y, int pointer, arc.input.KeyCode button) {
                if (bar == null) return;
                savePosition();
                // 未拖动（位移小于阈值）才视为单击
                if (!dragged && Math.abs(e.stageX - startX) < Scl.scl(5f) && Math.abs(e.stageY - startY) < Scl.scl(5f)) {
                    onClick.run();
                }
            }
        });
    }

    /** 给拖动把手挂拖动监听：touchDown 返回 true 取得焦点，移动整条并持久化位置 */
    private static void makeDraggable(Element target) {
        target.addListener(new InputListener() {
            float lastX, lastY;

            @Override
            public boolean touchDown(InputEvent e, float x, float y, int pointer, arc.input.KeyCode button) {
                lastX = e.stageX;
                lastY = e.stageY;
                return true;
            }

            @Override
            public void touchDragged(InputEvent e, float x, float y, int pointer) {
                moveBar(e.stageX - lastX, e.stageY - lastY);
                lastX = e.stageX;
                lastY = e.stageY;
            }

            @Override
            public void touchUp(InputEvent e, float x, float y, int pointer, arc.input.KeyCode button) {
                if (bar == null) return;
                savePosition();
            }
        });
    }

    /** 拖动即移动「左上角」；夹取保证整条（含收起态按钮）始终完整留在屏幕内 */
    private static void moveBar(float dx, float dy) {
        if (bar == null) return;
        bar.x += dx;
        bar.y += dy;
        clampBar();
    }

    /** 以左上角为基准夹取：x ∈ [m, w-width-m]，top ∈ [m+height, h-m]（场景 y 向上） */
    private static void clampBar() {
        if (bar == null) return;
        float m = Scl.scl(2f);
        float maxX = Math.max(m, Core.graphics.getWidth() - bar.getWidth() - m);
        bar.x = Mathf.clamp(bar.x, m, maxX);
        float minTop = m + bar.getHeight();
        float maxTop = Math.max(minTop, Core.graphics.getHeight() - m);
        float top = Mathf.clamp(bar.y + bar.getHeight(), minTop, maxTop);
        bar.y = top - bar.getHeight();
    }

    /** 持久化当前位置（左上角锚点：x + 上边缘） */
    private static void savePosition() {
        if (bar == null) return;
        Core.settings.put(CFG_X, bar.x);
        Core.settings.put(CFG_TOP, bar.y + bar.getHeight());
    }

    /** 移除当前控制条：下次 update 依据 collapsed 重建 */
    private static void detach() {
        if (bar == null) return;
        bar.remove();
        bar = null;
        playBtn = null;
        collapsedBtn = null;
    }

    /** 重置悬浮条位置到默认：贴左边缘 + 屏幕高度 {@link #DEFAULT_TOP_FRAC} 处（清除记忆位置设置） */
    public static void resetPosition() {
        Core.settings.remove(CFG_X);
        Core.settings.remove(CFG_TOP);
        Core.settings.remove(CFG_Y_LEGACY);
        if (bar != null) {
            bar.remove();
            bar = null;
        }
    }

    private static String trackLabel() {
        MusicTrack t = MusicPlayer.currentTrack();
        if (t == null) {
            try { String v = Core.bundle.get("musicplayer.none"); return v != null && !v.contains("??") ? v : "none"; } catch (Exception e) { return "none"; }
        }
        String safe = t.name == null ? "" : t.name.replace("[", "[[").replace("]", "]]");
        if (MusicPlayer.isPlaying()) return "[accent]> " + safe;
        return safe;
    }

    private static String albumScopeLabel() {
        String active = MusicPlayer.activeAlbum();
        if (active == null) {
            try { String v = Core.bundle.get("musicplayer.allAlbums"); return v != null && !v.contains("??") ? v : "all"; } catch (Exception e) { return "all"; }
        }
        // 问题3：不显示「专辑：」前缀，按钮直接显示专辑名，避免冗余前缀
        return active;
    }

    /** 悬浮条当前/总时长文本；未知时长显示 0:00 */
    private static String formatFloaterTime(float cur, float len) {
        if (Float.isNaN(cur) || Float.isInfinite(cur)) cur = 0f;
        if (Float.isNaN(len) || Float.isInfinite(len)) len = -1f;
        return MusicPlayer.formatTimeSimple(cur) + " / " + (len > 0f ? MusicPlayer.formatTimeSimple(len) : "--:--");
    }

    /** 悬浮条倍速按钮文字：当前倍速（整数倍省略小数，小数为两位） */
    private static String speedLabel() {
        float s = MusicPlayer.speed();
        if (Math.abs(s - 1f) < 0.001f) return "1x";
        if (Math.abs(s - Math.round(s)) < 0.001f) return Math.round(s) + "x";
        return String.format(java.util.Locale.US, "%.2fx", s);
    }

    /** 悬浮条循环按钮文字：当前循环模式（6 种中文文案，缺键回退） */
    private static String loopModeLabel() {
        try { String v = Core.bundle.get("musicplayer.loopmode." + MusicPlayer.loopMode()); return v != null && !v.contains("??") ? v : ("loop" + MusicPlayer.loopMode()); } catch (Exception e) { return "loop" + MusicPlayer.loopMode(); }
    }

    /** 在「全部曲目」与各专辑间轮换激活专辑作用域（点按悬浮条专辑按钮） */
    private static void cycleAlbumScope() {
        arc.struct.Seq<MusicPlayer.Album> albums = MusicPlayer.albums();
        String active = MusicPlayer.activeAlbum();
        if (albums.size == 0) return;
        int idx = -1;
        if (active != null) {
            for (int i = 0; i < albums.size; i++) {
                if (active.equals(albums.get(i).name)) { idx = i; break; }
            }
        }
        // idx==-1（全部或无匹配）→ 切到第 0 个专辑；否则切到下一个，末尾切回「全部」
        String next = (idx == -1) ? albums.get(0).name : (idx + 1 < albums.size ? albums.get(idx + 1).name : null);
        MusicPlayer.setActiveAlbum(next);
    }
}
