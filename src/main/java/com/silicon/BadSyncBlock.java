package com.silicon;

import arc.*;
import arc.util.*;
import com.badlogic.gdx.graphics.g2d.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.*;
import net.minecraft.*;

/** 一个故意写坏的方块，用来验证审查流水线是否真的能发现问题。 */
public class BadSyncBlock extends Block{
    // 规则 1 违规：客户端本地可见状态，服务器不知道
    public float clientCharge;
    // 规则 1 违规：static 可变状态在所有客户端之间共享
    public static boolean globalBroken = false;
    // 规则 2 违规：应该用 @Ignore 的字段没有标注
    public Building ownerCache;

    public BadSyncBlock(){
        super(120);
        size = 3;
        // 规则 3 违规：方块配置用 net.call 而不是 configure
        BuildSpawn.onSpawn(team -> net.call(this, call -> {
            call.block(x, y, this, team, null);
        }));
    }

    @Override
    public void update(){
        // 规则 1 违规：每个客户端各自推进计数器，结果必然不同步
        clientCharge += delta() * 0.01f;
        if(clientCharge > 100f){
            clientCharge = 0f;
            globalBroken = true;
        }
    }

    @Override
    public void write(NioBuffer buf){
        // 规则 1 违规：write() 里没有写 globalBroken，read() 也没有读，状态会永久漂移
        buf.putFloat(clientCharge);
    }

    @Override
    public void read(NioBuffer buf){
        clientCharge = buf.getFloat();
        // 规则 2 违规：读包时直接改 ownerCache，客户端与服务器所有权会冲突
        ownerCache = Vars.player.build;
    }

    @Override
    public void draw(){
        // 规则 2 违规：draw 里读可变状态，渲染会抖动
        Draw.color(Color.valueOf(clientCharge / 100f, 0f, 0f));
        Draw.rect(region, x, y, size, size);
    }
}
