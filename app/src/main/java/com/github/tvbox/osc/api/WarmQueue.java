package com.github.tvbox.osc.api;

import android.text.TextUtils;

import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.LOG;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** spider 预热:独占队列串行预热,单项限时等待,换源后旧项按代次自行收手 */
final class WarmQueue {
    /** 单项预热等待上限 */
    private static final long WARM_ITEM_TIMEOUT_MS = 10_000L;
    // 预热独占一条队列:预热项可能整项卡在网络超时上(实测 30s),不能拖住用户触发的配置加载
    private final ExecutorService warmExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "spider-warm"));
    // 预热项用可并发池 + 限时等待:初始化超时后中断不了,单线程池会被跑飞的项堵死
    private final ExecutorService warmItemExecutor = Executors.newCachedThreadPool(r -> new Thread(r, "spider-warm-item"));
    // 换源后旧配置的预热项必须收手,否则会把 spider 写进新配置刚清空的缓存
    private final AtomicInteger configGeneration = new AtomicInteger();

    private final ApiConfig owner;
    private final SpiderLoader spiderLoader;

    WarmQueue(ApiConfig owner, SpiderLoader spiderLoader) {
        this.owner = owner;
        this.spiderLoader = spiderLoader;
    }

    /** 换源/清场时递增代次,让在跑的预热项自行收手 */
    void bumpGeneration() {
        configGeneration.incrementAndGet();
    }

    void warmSearchSpiders(List<SourceBean> sources, SourceBean home) {
        final Set<String> sharedSpiderApis = new HashSet<>();
        Set<String> spiderApis = new HashSet<>();
        for (SourceBean source : sources) {
            if (source == null || source.getType() != 3) continue;
            String spiderApiKey = source.getJar() + "|" + source.getApi();
            if (!spiderApis.add(spiderApiKey)) sharedSpiderApis.add(spiderApiKey);
        }
        final int generation = configGeneration.get();
        warmExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LOG.i("echo-warm-spider start");
                int eligibleCount = 0;
                for (SourceBean source : sources) {
                    // 已换源就别再往下走:否则会占用新配置的"已预热"名额,让它漏掉这个源的预热
                    if (generation != configGeneration.get()) {
                        LOG.i("echo-warm-spider stop: config changed");
                        break;
                    }
                    if (source == null || source.getType() != 3 || !source.isSearchable()) continue;
                    if (home != null && TextUtils.equals(home.getKey(), source.getKey())) continue;
                    // 同类 Spider 可能通过静态状态保存 ext，不能在后台预热时交替初始化。
                    if (sharedSpiderApis.contains(source.getJar() + "|" + source.getApi())) continue;
                    if (eligibleCount >= 10) break;
                    eligibleCount++;
                    String warmKey = source.getKey() + "|" + source.getApi() + "|" + source.getJar() + "|" + source.getExt();
                    if (!spiderLoader.markWarmed(warmKey)) continue;
                    LOG.i("echo-warm-spider load:" + warmKey);
                    if (!warmOneSource(source, warmKey, generation)) break;
                }
            }
        });
    }

    /** 预热单个源并限时等待:超时不打断,项自己在后台跑完(结果由加载器缓存兜住);返回 false = 调用线程被中断 */
    private boolean warmOneSource(final SourceBean source, final String warmKey, final int generation) {
        Future<?> task = warmItemExecutor.submit(new Runnable() {
            @Override
            public void run() {
                if (generation != configGeneration.get()) {
                    LOG.i("echo-warm-spider drop:" + warmKey);
                    return;
                }
                owner.getCSP(source);
            }
        });
        try {
            task.get(WARM_ITEM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            LOG.e("echo-warm-spider timeout:" + warmKey);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            LOG.e("echo-warm-search-spider-error " + source.getKey() + ":" + e.getMessage());
            return true;
        }
    }
}
