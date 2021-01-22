/*
 *    MTreeMicroClusters.java
 *    Copyright (C) 2013 Aristotle University of Thessaloniki, Greece
 *    @author D. Georgiadis, A. Gounaris, A. Papadopoulos, K. Tsichlas, Y. Manolopoulos
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *    
 *    
 */

package moa.clusterers.outliers.MCODmod1;

import moa.clusterers.outliers.utils.mtree.*;
import moa.clusterers.outliers.utils.mtree.utils.Pair;
import moa.clusterers.outliers.utils.mtree.utils.Utils;

import java.util.Set;

class MTreeMicroClusters extends MTree<MicroCluster> {

    private static final PromotionFunction<MicroCluster> nonRandomPromotion = new PromotionFunction<MicroCluster>() {

        @Override
        public Pair<MicroCluster> process(Set<MicroCluster> dataSet, DistanceFunction<? super MicroCluster> distanceFunction) {
            return Utils.minMax(dataSet);
        }
    };

    MTreeMicroClusters() {
        super(2, DistanceFunctions.EUCLIDEAN,
                new ComposedSplitFunction<MicroCluster>(
                nonRandomPromotion,
                new PartitionFunctions.BalancedPartition<MicroCluster>()));
    }

    public void add(MicroCluster data) {
        super.add(data);
        _check();
    }

    public boolean remove(MicroCluster data) {
        boolean result = super.remove(data);
        _check();
        return result;
    }

    DistanceFunction<? super MicroCluster> getDistanceFunction() {
        return distanceFunction;
    }
};
