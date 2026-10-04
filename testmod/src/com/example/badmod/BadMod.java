package com.example.badmod;
import org.loader.runtime.mod.ModContext;
public class BadMod {
    public void initialize(ModContext ctx) {
        ctx.logger().info("BadMod about to fail intentionally");
        throw new RuntimeException("intentional failure for isolation test");
    }
}
