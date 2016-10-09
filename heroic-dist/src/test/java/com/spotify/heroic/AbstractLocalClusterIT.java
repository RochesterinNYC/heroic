package com.spotify.heroic;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.spotify.heroic.cluster.ClusterManagerModule;
import com.spotify.heroic.cluster.discovery.simple.StaticListDiscoveryModule;
import com.spotify.heroic.profile.MemoryProfile;
import com.spotify.heroic.rpc.jvm.JvmRpcContext;
import com.spotify.heroic.rpc.jvm.JvmRpcProtocolModule;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.TinyAsync;
import org.junit.After;
import org.junit.Before;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public abstract class AbstractLocalClusterIT {
    protected final ExecutorService executor = Executors.newSingleThreadExecutor();
    protected final TinyAsync async = TinyAsync.builder().executor(executor).build();

    protected List<HeroicCoreInstance> instances;

    /**
     * Override to configure more than one instance.
     * <p>
     * These will be available in the {@link #instances} field.
     *
     * @return a list of URIs.
     */
    protected List<URI> instanceUris() {
        return ImmutableList.of(URI.create("jvm://a"), URI.create("jvm://b"));
    }

    /**
     * Prepare the environment before the test.
     * <p>
     * {@link #instances} have been configured before this is called and can be safely used.
     *
     * @return a future that indicates that the environment is ready.
     */
    protected AsyncFuture<Void> prepareEnvironment() {
        return async.resolved(null);
    }

    @Before
    public final void abstractSetup() throws Exception {
        final JvmRpcContext context = new JvmRpcContext();

        final List<URI> uris = instanceUris();
        final List<Integer> expectedNumberOfNodes =
            uris.stream().map(u -> uris.size()).collect(Collectors.toList());

        instances =
            uris.stream().map(uri -> setupCore(uri, uris, context)).collect(Collectors.toList());

        final AsyncFuture<Void> startup = async.collectAndDiscard(
            instances.stream().map(HeroicCoreInstance::start).collect(Collectors.toList()));

        // Refresh all cores, allowing them to discover each other.
        final AsyncFuture<Void> refresh = startup.lazyTransform(v -> async.collectAndDiscard(
            instances
                .stream()
                .map(c -> c.inject(inj -> inj.clusterManager().refresh()))
                .collect(Collectors.toList())));

        refresh.lazyTransform(v -> {
            // verify that the correct number of nodes are visible from all instances.
            final List<Integer> actualNumberOfNodes = instances
                .stream()
                .map(c -> c.inject(inj -> inj.clusterManager().getNodes().size()))
                .collect(Collectors.toList());

            assertEquals(expectedNumberOfNodes, actualNumberOfNodes);
            return prepareEnvironment();
        }).get(10, TimeUnit.SECONDS);
    }

    @After
    public final void abstractTeardown() throws Exception {
        async
            .collectAndDiscard(
                instances.stream().map(HeroicCoreInstance::shutdown).collect(Collectors.toList()))
            .get(10, TimeUnit.SECONDS);
    }

    private HeroicCoreInstance setupCore(
        final URI uri, final List<URI> uris, final JvmRpcContext context
    ) {
        try {
            return setupCoreThrowing(uri, uris, context);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private HeroicCoreInstance setupCoreThrowing(
        final URI uri, final List<URI> uris, final JvmRpcContext context
    ) throws Exception {
        final JvmRpcProtocolModule protocol =
            JvmRpcProtocolModule.builder().context(context).bindName(uri.getHost()).build();

        return HeroicCore
            .builder()
            .setupShellServer(false)
            .setupService(false)
            .oneshot(true)
            .executor(executor)
            .configFragment(HeroicConfig
                .builder()
                .cluster(ClusterManagerModule
                    .builder()
                    .tags(ImmutableMap.of("shard", uri.getHost()))
                    .protocols(ImmutableList.of(protocol))
                    .discovery(new StaticListDiscoveryModule(uris))))
            .profile(new MemoryProfile())
            .modules(HeroicModules.ALL_MODULES)
            .build()
            .newInstance();
    }
}
