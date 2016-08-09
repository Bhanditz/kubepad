/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 QAware GmbH, Munich, Germany
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.qaware.cloud.nativ.kpad

import de.qaware.cloud.nativ.kpad.launchpad.LaunchpadMK2
import org.slf4j.Logger
import java.util.concurrent.ExecutorService
import javax.annotation.PreDestroy
import javax.enterprise.context.ApplicationScoped
import javax.enterprise.event.Event
import javax.enterprise.event.Observes
import javax.enterprise.util.AnnotationLiteral
import javax.inject.Inject
import javax.inject.Named

/**
 * The grid of 8x8 of the cloud nodes.
 */
@ApplicationScoped
open class ClusterNodeGrid @Inject constructor(@Named("default")
                                               private val executor: ExecutorService,
                                               private val events: Event<ClusterNodeEvent>,
                                               private val cluster: Cluster,
                                               private val logger: Logger) {
    private var initialized : Boolean = false

    private val grid = arrayOf(
            mutableListOf<ClusterNode>(), mutableListOf<ClusterNode>(),
            mutableListOf<ClusterNode>(), mutableListOf<ClusterNode>(),
            mutableListOf<ClusterNode>(), mutableListOf<ClusterNode>(),
            mutableListOf<ClusterNode>(), mutableListOf<ClusterNode>())

    private val colors = Array(8, {i -> LaunchpadMK2.Color.values()[i + 1]})

    /**
     * Initialize the cloud node grid.
     */
    open fun init() {
        logger.info("Initialize 8x8 cluster node grid.")

        grid.forEachIndexed { row, instances ->
            IntRange(0, 7).forEach { instances.add(ClusterNode(row, it)) }
        }

        0.until(cluster.appCount()).forEach { appIndex ->
            val nodes = grid[appIndex]

            cluster.replicas(appIndex).forEachIndexed { i, replica ->
                logger.debug("Activating pod {}.", replica.name())

                val node = nodes[i].activate()
                node.phase = replica.phase()

                updateClusterNode(appIndex, node)
            }
        }
        initialized = true
    }

    @PreDestroy
    open fun shutdown() {
        cluster.clear()
        grid.forEach { row ->
            row.forEach { it.deactivate() }
        }
    }

    private fun updateClusterNode(row: Int, node: ClusterNode) {
        when (node.phase) {
            ClusterNode.Phase.Pending -> starting(row, node)
            ClusterNode.Phase.Running -> started(row, node)
            ClusterNode.Phase.Succeeded -> stopping(row, node)
            ClusterNode.Phase.Terminated -> stopped(row, node)
            else -> {
                // currently we can not handle the other Phases
                // by different colors
                // Unknown should be treated as stopped?
                // Failed should be treated as stopping?
            }
        }
    }

    /**
     * Start a new node in the cluster grid.
     *
     * @param event the node event data
     */
    open fun start(@Observes @ClusterNodeEvent.Start event: ClusterNodeEvent) {
        logger.info("Start cluster node {}", event)

        val node = grid[event.row][event.column]
        val running = grid[event.row].count { it.active.get() }

        updateClusterNode(event.row, node.activate())
        executor.execute { cluster.scale(event.row, running + 1) }
    }

    /**
     * Stop a node in the cluster grid.
     *
     * @param event the node event data
     */
    open fun stop(@Observes @ClusterNodeEvent.Stop event: ClusterNodeEvent) {
        logger.info("Stop cluster node {}", event)

        val node = grid[event.row][event.column]
        val running = grid[event.row].count { it.active.get() }

        stopping(event.row, node)
        node.deactivate()
        executor.execute { cluster.scale(event.row, running - 1) }
        stopped(event.row, node)
    }

    /**
     * Scale the deployment at given row to number of specified replicas.
     *
     * @param row the row
     * @param replicas number of replicas
     */
    open fun scale(row: Int, replicas: Int) {
        val running = active(row)
        executor.execute { cluster.scale(row, replicas) }

        if (running > replicas) {
            // scale down, stop nodes and update display
            val toStop = running - replicas - 1
            (0..toStop).forEach {
                val node = grid[row][last(row)]
                stopping(row, node)
                node.deactivate()
                stopped(row, node)
            }
        } else if (running < replicas) {
            // scale up, start nodes and update display
            val toStart = replicas - running - 1
            (0..toStart).forEach {
                val node = grid[row][next(row)]
                starting(row, node)
                node.activate()
                started(row, node)
            }
        }
    }

    /**
     * The event callback in case there is a deployment event in the cluster.
     *
     * @param event the deployment event
     */
    open fun onAppEvent(@Observes event: ClusterAppEvent) {
        if(!initialized)
            return

        val nodes = grid[event.index]
        when (event.type) {
            ClusterAppEvent.Type.ADDED -> {
                if(event.labels.containsKey("LAUNCHPAD_COLOR")) {
                    try {
                        colors[event.index] = LaunchpadMK2.Color.valueOf(event.labels["LAUNCHPAD_COLOR"]!!)
                    } catch (e : Exception) {
                        logger.error("Unknown color: {}!", event.labels["LAUNCHPAD_COLOR"])
                    }
                }

                IntRange(0, event.replicas - 1).forEach {
                    val node = nodes[it]
                    updateClusterNode(event.index, node.activate())
                }

                // watch(event.index, event.labels)
            }
            ClusterAppEvent.Type.DELETED -> {
                IntRange(0, 7).forEach {
                    val node = nodes[it]
                    if (node.active.get()) {
                        stopping(event.index, node)
                        node.deactivate()
                        stopped(event.index, node)
                    }
                }
            }
            ClusterAppEvent.Type.SCALED_UP -> {
                val nonRunning = nodes.filter { !it.active.get() }
                val toStart = event.replicas - (nodes.size - nonRunning.size)
                val range = 0..Math.min(toStart - 1, nonRunning.size - 1)
                range.forEach {
                    val node = nonRunning[it]
                    updateClusterNode(event.index, node.activate())
                }
            }
            ClusterAppEvent.Type.SCALED_DOWN -> {
                val running = nodes.filter { it.active.get() }
                val toStop = running.size - event.replicas
                running.reversed().subList(0, toStop).forEach {
                    stopping(event.index, it)
                    it.deactivate()
                    stopped(event.index, it)
                }
            }
        }
    }

    private fun starting(row: Int, node: ClusterNode) {
        events.select(object : AnnotationLiteral<ClusterNodeEvent.Starting>() {})
                .fire(ClusterNodeEvent(row, node.column))
    }

    private fun started(row: Int, node: ClusterNode) {
        events.select(object : AnnotationLiteral<ClusterNodeEvent.Started>() {})
                .fire(ClusterNodeEvent(row, node.column))
    }

    private fun stopping(row: Int, node: ClusterNode) {
        events.select(object : AnnotationLiteral<ClusterNodeEvent.Stopping>() {})
                .fire(ClusterNodeEvent(row, node.column))
    }

    private fun stopped(row: Int, node: ClusterNode) {
        events.select(object : AnnotationLiteral<ClusterNodeEvent.Stopped>() {})
                .fire(ClusterNodeEvent(row, node.column))
    }

    open fun color(row: Int) : LaunchpadMK2.Color {
        return if (colors.indices.contains(row)) colors[row] else LaunchpadMK2.Color.LIGHT_GREEN
    }

    /**
     * Get the next not running instance index for the given row.
     *
     * @param row the row index
     */
    open fun next(row: Int): Int {
        val nodes = grid[row]
        return nodes.find { !it.active.get() }!!.column
    }

    /**
     * Find the last running instance index for the given row.
     *
     * @param row the row index
     */
    open fun last(row: Int): Int {
        val nodes = grid[row]
        return nodes.findLast { it.active.get() }!!.column
    }

    open operator fun get(row: Int): List<ClusterNode> {
        return grid[row]
    }

    /**
     * Returns the number of active nodes for a row.
     *
     * @param row the row
     * @return number of active nodes
     */
    open fun active(row: Int): Int = grid[row].count { it.active.get() }

    /**
     * Check if the given row is initialized meaning is there a deployment.
     *
     * @param row the row
     * @return true if row is initialized with a deployment
     */
    open fun initialized(row: Int): Boolean = cluster.appExists(row)

    /**
     * Returns the set of row indexes that have a deployment.
     *
     * @return a set of row index with an associated deployment
     */
    open fun rows(): List<Int> {
        return 0.until(cluster.appCount()).filter { i -> cluster.appExists(i) }
    }
}
