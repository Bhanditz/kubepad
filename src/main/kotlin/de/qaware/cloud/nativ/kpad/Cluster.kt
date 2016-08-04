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

interface Cluster {

    /**
     * @return the size of the list of apps. This number may be lager than the actual number of currently deployed apps,
     * as there is not an app at every index.
     * @see Cluster.appExists()
     */
    fun appCount() : Int

    /**
     * Check if there is an app deployed at the given index
     *
     * @param appIndex the index (row) to be checked
     * @return true if an app is deployed at the given index
     */
    fun appExists(appIndex : Int) : Boolean

    /**
     * Retrieves the list of all replicas of the app at the given index. Each of the replicas is represented by one
     * square on the launchpad.
     */
    fun replicas(appIndex : Int) : List<ClusterAppReplica>

    /**
     * Scale the app at the given index to a number of given replicas.
     *
     * @param index the deployment index on the Launchpad
     * @param replicas the number of replicas
     */
    fun scale(appIndex : Int, replicas : Int)

    /**
     * Clears the local list of apps.
     */
    fun clear()
}
