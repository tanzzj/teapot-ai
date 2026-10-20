package com.teamer.teapot.ai.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 每轮实例回收（线上内存泄漏修复）：Web 链路每轮对话重新 build 一个 HarnessAgent
 * （AgentRegistry 不缓存，配置即时生效），而 ReActAgent 构造时
 * 会把自身注册进 GracefulShutdownManager.stateSavers（键为实例 UUID，永不过期），
 * 其 stateCache 又持有本轮从 MySQL 载入的整份会话快照——只有 close() 会解绑并清空。
 * 于是每轮对话留下一条永久引用链（MAT：单例占 1.7GB 堆的 92%），会话越长每轮复制的整份历史越多，
 * 增长是平方级。SDK 契约（AguiUtil）：非「live session」的实例由调用方负责 close，
 * teapot 的调用方是 AG-UI starter（无 close 点），故由链路入口 AgentRegistry 挂本中间件在轮末代劳。
 *
 * <p>装配契约：只用于「一轮一个新实例」的链路（Web/AG-UI 与同步调试 chat，见 AgentRegistry）。
 * 长生命周期实例绝不能挂——channel 链路（ChannelHub）启动时 build 一个实例服务其后全部消息，
 * 挂上本中间件会在第一条消息处理完就把该实例关掉。
 *
 * <p>挂载位置必须在 onAgent 链最外层（装配顺序的第一个，见 MiddlewareChain：first = outermost），
 * 保证 close 发生在全部中间件收尾与轮末状态落盘之后；一次实例只关一次（getAndSet），
 * 取消（SSE 断连）同样回收。close() 含 MCP 客户端/沙箱句柄释放与最长 5s 的后台记忆落盘等待，
 * 属阻塞操作，调度到 boundedElastic，不占 SSE 发射线程。
 */
@Slf4j
public class SingleUseAgentCloseMiddleware implements MiddlewareBase {

    private final String agentKey;
    private final AtomicReference<HarnessAgent> owner = new AtomicReference<>();

    public SingleUseAgentCloseMiddleware(String agentKey) {
        this.agentKey = agentKey;
    }

    /**
     * 绑定承载本中间件的实例（build 之后调用）：中间件收到的 agent 为内部 ReActAgent
     * （见 PerMessageCheckpointMiddleware 同款说明），只关它不足以回收 MCP 客户端与工作区索引，
     * 必须关外层 HarnessAgent。
     */
    public void bind(HarnessAgent agent) {
        owner.set(agent);
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).doFinally(signal -> {
            HarnessAgent owned = owner.getAndSet(null);
            if (owned == null) {
                log.warn("轮末回收跳过（未绑定实例）agentKey={} signal={}", agentKey, signal);
                return;
            }
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    owned.close();
                    log.debug("轮末实例已回收 agentKey={} signal={}", agentKey, signal);
                } catch (Exception e) {
                    log.warn("轮末实例回收失败 agentKey={} signal={} err={}",
                            agentKey, signal, e.toString());
                }
            });
        });
    }
}
