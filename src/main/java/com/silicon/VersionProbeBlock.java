package com.silicon;

import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.blocks.*;

/** 版本号检查验证用：新增一个方块（按规范应递增 Minor 位），但故意不动 mod.hjson。 */
public class VersionProbeBlock extends Block{
    public VersionProbeBlock(){
        super(80);
        size = 2;
        oreType = oreCopper;
        requirements(Category.power, 0.5f, 30f, 60f);
    }

    @Override
    public void draw(){
        Draw.rect(region, x, y, size, size);
    }
}
