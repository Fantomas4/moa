/*
 *    MCOD.java
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

import com.github.javacliparser.FloatOption;
import com.github.javacliparser.IntOption;
import com.yahoo.labs.samoa.instances.Instance;
import moa.clusterers.outliers.MCODmod1.ISBIndex.ISBNode;
import moa.clusterers.outliers.MCODmod1.ISBIndex.ISBNode.NodeType;
import moa.clusterers.outliers.MCODmod1.ISBIndex.ISBSearchResult;

import java.util.ArrayList;
import java.util.TreeSet;
import java.util.Vector;


//The algorithm is described in 
// M. Kontaki, A. Gounaris, A. N. Papadopoulos, K. Tsichlas, and Y. Manolopoulos. 
//Continuous monitoring of distance-based outliers over data streams.
//In ICDE, pages 135–146, 2011.


public class MCOD extends MCODBase {
    public FloatOption radiusOption = new FloatOption("radius", 'r', "Search radius.", 0.1);
    public IntOption kOption = new IntOption("k", 't', "Parameter k.", 50);
    public FloatOption arFactorOption = new FloatOption("ApproximationRadiusFactor", 'b',
            "A factor b that determines the search radius relevant to R/2 for an MC's approximate neighbors (ar = R/2 + b * R/2",
            0.5);
    public FloatOption mcApproxFactor = new FloatOption("MCApproximationFactor", 'c',
            "A factor c that determines the maximum amount of approximate nodes that can be included in a viable MC (apprNodeNumber = c * mcSize ",
            0.2);

    // DIAG ONLY -- DELETE
    int diagExactMCCount = 0;
    int diagApproxMCCount = 0;
    int diagDiscardedMCCount = 0;
    int diagTotalActiveMCCount = 0;
    int diagTotalPointsAddedToMC = 0;
    int diagPDListPopulation = 0;

    public MCOD()
    {
        // System.out.println("MCOD: created");
    }
    
    @Override
    public void Init() {   
        super.Init();
        
        m_WindowSize = windowSizeOption.getValue();
        m_radius = radiusOption.getValue();
        m_k = kOption.getValue();
        m_arFactor = arFactorOption.getValue();
        m_mcApproxFactor = mcApproxFactor.getValue();
                
        Println("Init MCODmod1:");
        Println("   window_size: " + m_WindowSize);
        Println("   radius: " + m_radius);
        Println("   k: " + m_k);
        Println("   ApproximationRadiusFactor: " + m_arFactor);
        Println("   MCApproximationFactor: " + m_mcApproxFactor);
        
        //bTrace = true;
        //bWarning = true;
        
        objId = FIRST_OBJ_ID; // init object identifier
        // create nodes list of window
        windowNodes = new Vector<ISBNode>();
        // create ISB
        ISB_PD = new ISBIndex(m_radius, m_k);
        // create helper sets for micro-cluster management
        setMC = new TreeSet<MicroCluster>();
        // micro-cluster index
        mtreeMC = new MTreeMicroClusters();
        // create event queue
        eventQueue = new EventQueue();
        
        // init statistics
        m_nBothInlierOutlier = 0;
        m_nOnlyInlier = 0;
        m_nOnlyOutlier = 0;
    }
    
    void SetNodeType(ISBNode node, NodeType type) {
        node.nodeType = type;
        // update statistics
        if (type == NodeType.OUTLIER)
            node.nOutlier++;
        else
            node.nInlier++;
    }
    
    void AddNeighbor(ISBNode node, ISBNode q, boolean bUpdateState) {
        if (bTrace) Println("AddNeighbor: node.id: " + node.id + ", q.id: " + q.id);
        
        // check if q still in window
        if (IsNodeIdInWin(q.id) == false) {
            if (bWarning) Println("AddNeighbor: node.id: " + node.id + ", q.id: " + q.id + " (expired)"); 
            return;
        }
        
        if (q.id < node.id) {
            node.AddPrecNeigh(q);
        } else {
            node.count_after++;
        }        
        
        if (bUpdateState) {
            // check if node inlier or outlier
            int count = node.count_after + node.CountPrecNeighs(GetWindowStart());
            if ((node.nodeType == NodeType.OUTLIER) && (count >= m_k)) {
                // remove node from outliers
                if (bTrace) Println("Remove node from outliers"); 
                RemoveOutlier(node);
                // add node to inlier set PD
                SetNodeType(node, NodeType.INLIER_PD);
                // insert node to event queue
                ISBNode nodeMinExp = node.GetMinPrecNeigh(GetWindowStart());
                AddToEventQueue(node, nodeMinExp);
            }
        }
    }
    
    void ProcessNewNode(ISBNode nodeNew, boolean bNewNode) {
        if (bTrace) { Print("ProcessNewNode: "); PrintNode(nodeNew); }
        
        if (bTrace) Println("Perform 3R/2 range query to cluster centers w.r.t new node"); 
        Vector<SearchResultMC> resultsMC;
        // results are sorted ascenting by distance
        resultsMC = RangeSearchMC(nodeNew, 1.5 * m_radius); 
        if (bTrace) {
            Println("MC query found: "); 
            for (SearchResultMC sr : resultsMC) {
                Printf("  (%.1f) mcc: ", sr.distance); PrintNode(sr.mc.mcc);
            }
        }
        
        if (bTrace) Println("Get closest micro-cluster"); 
        MicroCluster mcClosest = null;
        if (resultsMC.size() > 0) { 
            mcClosest = resultsMC.get(0).mc;
            if (bTrace) Println("Closest mcc: " + mcClosest.mcc.id);
        }
        
        // check if nodeNew can be insterted to closest micro-cluster
        boolean bFoundMC = false;
        if (mcClosest != null) {
            double d = GetEuclideanDist(nodeNew, mcClosest.mcc);
            if (d <= m_radius / 2) {
                bFoundMC = true;
            } else {
                if (bTrace) Println("Not close enough to closest mcc"); 
            }
        }
        
        if (bFoundMC) {
            if (bTrace) Println("Add new node to micro-cluster");
            // DIAG ONLY -- DELETE
            diagTotalPointsAddedToMC ++;
            diagTotalActiveMCCount ++;

            nodeNew.mc = mcClosest;
            SetNodeType(nodeNew, NodeType.INLIER_MC);
            mcClosest.AddNode(nodeNew);
            if (bTrace) { Print("mcClosest.nodes: "); PrintNodeList(mcClosest.nodes); } 
            
            if (bTrace) Println("Update neighbors of set PD"); 
            Vector<ISBNode> nodes;
            nodes = ISB_PD.GetAllNodes();
            for (ISBNode q : nodes) {
                if (q.Rmc.contains(mcClosest)) {
                    if (GetEuclideanDist(q, nodeNew) <= m_radius) {
                        if (bNewNode) {
                            // update q.count_after and its' outlierness
                            AddNeighbor(q, nodeNew, true);
                        } else {
                            if (nodesReinsert.contains(q)) {
                                // update q.count_after or q.nn_before and its' outlierness
                                AddNeighbor(q, nodeNew, true);
                            }
                        }
                    }
                }
            }
        }
        else {
            // No close enough micro-cluster found.
            // Perform 3R/2 range query to nodes in set PD.
            if (bTrace) Println("Perform custom range query to nodes in set PD");
            nRangeQueriesExecuted++;
            // Determine query range
            double ar = (m_radius / 2.0) + (m_arFactor * m_radius); // Approximate query range determined by arFactor
            double qRange = Math.max(1.5 * m_radius, ar);
            // create helper sets for micro-cluster management
            ArrayList<ISBNode> setNC = new ArrayList<ISBNode>();  // Neighbors to Cluster
            ArrayList<ISBNode> setNNC = new ArrayList<ISBNode>(); // Not Neighbors to Cluster
            ArrayList<ISBNode> setANC = new ArrayList<ISBNode>(); // Approximate Neighbors to Clusters
            Vector<ISBSearchResult> resultNodes;
            // Result nodes are returned in ascending order based on distance from nodeNew
            resultNodes = ISB_PD.RangeSearch(nodeNew, qRange);
            for (ISBSearchResult sr : resultNodes) {
                ISBNode q = sr.node;
                if (sr.distance <= m_radius) {                    
                    // add q to neighs of nodeNew
                    AddNeighbor(nodeNew, q, false);                
                    if (bNewNode) {
                        // update q.count_after and its' outlierness
                        AddNeighbor(q, nodeNew, true); 
                    } else {
                        if (nodesReinsert.contains(q)) {
                            // update q.count_after or q.nn_before and its' outlierness
                            AddNeighbor(q, nodeNew, true);
                        }
                    }
                }
                
                if (sr.distance <= m_radius / 2.0) {
                    setNC.add(q);
                } else {
                    setNNC.add(q);
                    if (sr.distance <= ar) setANC.add(q);
                }
            }
            if (bTrace) {
                Print("Prec neighs of new node: "); PrintNodeList(nodeNew.Get_nn_before());
                Print("NC: "); PrintNodeList(setNC); 
                Print("NNC: "); PrintNodeList(setNNC);
                Print("ANC: "); PrintNodeList(setANC);
            }

            // Calculate the upper limit of approximate objects for m_k size
            int approxObjLimit = Math.toIntExact(Math.round(m_mcApproxFactor * m_k));
            // Calculate the amount of needed approximate objects to achieve k micro-cluster objects
            int approxObjNeeded = m_k - setNC.size();
            if (approxObjNeeded < 0) approxObjNeeded = 0;
            
            // check if size of set NC big enough to create cluster
            if (bTrace) Println("Check size of set NC"); 
            if (setNC.size() >= m_theta * m_k) {
                // DIAG ONLY -- DELETE
                diagExactMCCount ++;
                diagTotalActiveMCCount ++;

                // create new micro-cluster with center nodeNew
                if (bTrace) Println("Create new micro-cluster"); 
                MicroCluster mcNew = new MicroCluster(nodeNew);
                AddMicroCluster(mcNew);
                nodeNew.mc = mcNew;
                // DIAG ONLY -- DELETE
                diagTotalPointsAddedToMC ++;
                SetNodeType(nodeNew, NodeType.INLIER_MC);
                
                if (bTrace) Println("Add to new mc nodes within range R/2"); 
                for (ISBNode q : setNC) {
                    q.mc = mcNew;
                    // DIAG ONLY -- DELETE
                    diagTotalPointsAddedToMC ++;
                    mcNew.AddNode(q);
                    // move q from set PD to set inlier-mc
                    SetNodeType(q, NodeType.INLIER_MC);
                    ISB_PD.Remove(q);
                    // DIAG ONLY -- DELETE
                    diagPDListPopulation --;
                    diagTotalPointsAddedToMC ++;
                    RemoveOutlier(q); // needed? ###
                }
                if (bTrace) { 
                    Print("mcNew.nodes: "); PrintNodeList(mcNew.nodes); 
                    PrintPD();
                } 
                
                if (bTrace) Println("Update Rmc lists of nodes of PD in range 3R/2 from mcNew"); 
                for (ISBNode q : setNNC) {
                    q.Rmc.add(mcNew);
                    if (bTrace) { Print(q.id + ".Rmc: "); PrintMCSet(q.Rmc); }
                }
            } else if (approxObjNeeded > 0 && approxObjNeeded <= approxObjLimit && setANC.size() >= approxObjNeeded) {
                // DIAG ONLY -- DELETE
                diagApproxMCCount ++;
                diagTotalActiveMCCount ++;

                // create new micro-cluster with center nodeNew
                if (bTrace) Println("Create new approximate micro-cluster");
                MicroCluster mcNew = new MicroCluster(nodeNew);
                AddMicroCluster(mcNew);
                nodeNew.mc = mcNew;
                SetNodeType(nodeNew, NodeType.INLIER_MC);

                // Add exact and approximate objects to the new approximate MC
                if (bTrace) Println("Add to new mc nodes within range R/2");
                for (ISBNode q : setNC) {
                    q.mc = mcNew;
                    mcNew.AddNode(q);
                    // move q from set PD to set inlier-mc
                    SetNodeType(q, NodeType.INLIER_MC);
                    ISB_PD.Remove(q);
                    // DIAG ONLY -- DELETE
                    diagPDListPopulation --;
                    diagTotalPointsAddedToMC ++;
                    RemoveOutlier(q); // needed? ###
                }
                if (bTrace) Println("Add to new mc nodes within the approximate query's range");
                for (int i = 0 ; i < approxObjNeeded; i++) {
                    ISBNode approxNode = setANC.get(i);
                    approxNode.mc = mcNew;
                    // move approxNode from set PD to set APPROX_INLIER_MC
                    SetNodeType(approxNode, NodeType.APPROX_INLIER_MC);
                    ISB_PD.Remove(approxNode);
                    // DIAG ONLY -- DELETE
                    diagPDListPopulation --;
                    diagTotalPointsAddedToMC ++;
                    RemoveOutlier(approxNode); // needed? ###
                }
                if (bTrace) {
                    Print("mcNew.nodes: "); PrintNodeList(mcNew.nodes);
                    PrintPD();
                }

                if (bTrace) Println("Update Rmc lists of nodes of PD in range 3R/2 from mcNew");
                for (ISBNode q : setNNC) {
                    q.Rmc.add(mcNew);
                    if (bTrace) { Print(q.id + ".Rmc: "); PrintMCSet(q.Rmc); }
                }
            } else {
                if (bTrace) Println("Add to nodeNew neighs nodes of near micro-clusters");
                for (SearchResultMC sr : resultsMC) {
                    for (ISBNode q : sr.mc.nodes) {
                        if (GetEuclideanDist(q, nodeNew) <= m_radius) {
                            // add q to neighs of nodeNew
                            AddNeighbor(nodeNew, q, false);
                        }
                    }
                }
                if (bTrace) {
                    Println("nodeNew.count_after = " + nodeNew.count_after);
                    Print("nodeNew.nn_before: "); PrintNodeList(nodeNew.Get_nn_before());
                }

                if (bTrace) Println("Insert nodeNew to index of nodes of PD");
                ISB_PD.Insert(nodeNew);
                // DIAG ONLY -- DELETE
                diagPDListPopulation ++;
                if (bTrace) PrintPD();

                // check if nodeNew is an inlier or outlier
                // use both nn_before and count_after for case bNewNode=false
                int count = nodeNew.CountPrecNeighs(GetWindowStart()) + nodeNew.count_after;
                if (count >= m_k) {
                    if (bTrace) Println("nodeNew is an inlier");
                    SetNodeType(nodeNew, NodeType.INLIER_PD);
                    // insert nodeNew to event queue
                    ISBNode nodeMinExp = nodeNew.GetMinPrecNeigh(GetWindowStart());
                    AddToEventQueue(nodeNew, nodeMinExp);
                } else {
                    if (bTrace) Println("nodeNew is an outlier");
                    SetNodeType(nodeNew, NodeType.OUTLIER);
                    SaveOutlier(nodeNew);
                }

                if (bTrace) Println("Update nodeNew.Rmc");
                for (SearchResultMC sr : resultsMC) {
                    nodeNew.Rmc.add(sr.mc);
                }
                if (bTrace) { Print("nodeNew.Rmc: "); PrintMCSet(nodeNew.Rmc); }
            }
        }
    }

    void AddToEventQueue(ISBNode x, ISBNode nodeMinExp) {
        if (bTrace) Println("AddToEventQueue x.id: " + x.id); 
        if (nodeMinExp != null) {
            Long expTime = GetExpirationTime(nodeMinExp);
            eventQueue.Insert(x, expTime);
            if (bTrace) {
                Print("x.nn_before: "); PrintNodeList(x.Get_nn_before());
                Println("nodeMinExp: " + nodeMinExp.id + ", expTime = " + expTime);
                PrintEventQueue();
            }
        } else {
            if (bWarning) Println("AddToEventQueue: Cannot add x.id: " + x.id + " to event queue (nn_before is empty, count_after=" + x.count_after + ")"); 
        }
    }
    
    void ProcessEventQueue(ISBNode nodeExpired) {
        EventItem e = eventQueue.FindMin();
        while ((e != null) && (e.timeStamp <= GetWindowEnd())) {
            e = eventQueue.ExtractMin();
            ISBNode x = e.node;
            if (bTrace) Println("Process event queue: check node x: " + x.id);
            // node x must be in window and not in any micro-cluster
            boolean bValid = ( IsNodeIdInWin(x.id) && (x.mc == null) );
            if (bValid) {
                // remove nodeExpired from x.nn_before
                x.RemovePrecNeigh(nodeExpired);
                // get amount of neighbors of x
                int count = x.count_after + x.CountPrecNeighs(GetWindowStart());
                if (count < m_k) {
                    if (bTrace) Println("x is an outlier");
                    SetNodeType(x, NodeType.OUTLIER);
                    SaveOutlier(x);
                } else {
                    if (bTrace) Println("x is an inlier, add to event queue");
                    // get oldest preceding neighbor of x
                    ISBNode nodeMinExp = x.GetMinPrecNeigh(GetWindowStart());
                    // add x to event queue
                    AddToEventQueue(x, nodeMinExp);
                }
            } else {
                if (bWarning) Println("Process event queue: node x.id: " + x.id + " is not valid!");
            }
            e = eventQueue.FindMin();
        }
    }

    void ProcessExpiredNode(moa.clusterers.outliers.MCODmod1.ISBIndex.ISBNode nodeExpired) {
        if (nodeExpired != null) {
            if (bTrace) Println("\nnodeExpired: " + nodeExpired.id);
            moa.clusterers.outliers.MCODmod1.MicroCluster mc = nodeExpired.mc;
            if (mc != null) {
                if (bTrace) Println("nodeExpired belongs to mc: " + mc.mcc.id);
                mc.RemoveNode(nodeExpired);
                if (bTrace) { Print("mc.nodes: "); PrintNodeList(mc.nodes); }

                if (bTrace) Println("Check if mc has enough objects");
                if (mc.GetNodesCount() < m_k) {
                    // DIAG ONLY -- DELETE
                    diagDiscardedMCCount ++;
                    diagTotalActiveMCCount --;

                    // remove micro-cluster mc
                    if (bTrace) Println("Remove mc");
                    RemoveMicroCluster(mc);

                    // insert nodes of mc to set nodesReinsert
                    nodesReinsert = new TreeSet<moa.clusterers.outliers.MCODmod1.ISBIndex.ISBNode>();
                    for (moa.clusterers.outliers.MCODmod1.ISBIndex.ISBNode q : mc.nodes) {
                        nodesReinsert.add(q);
                    }

                    // treat each node of mc as new node
                    for (moa.clusterers.outliers.MCODmod1.ISBIndex.ISBNode q : mc.nodes) {
                        if (bTrace) Println("\nTreat as new node q: " + q.id);
                        q.InitNode();
                        ProcessNewNode(q, false);
                    }
                }
            } else {
                // nodeExpired belongs to set PD
                // remove nodeExpired from PD index
                ISB_PD.Remove(nodeExpired);
                // DIAG ONLY -- DELETE
                diagPDListPopulation --;
            }

            RemoveNode(nodeExpired);
            ProcessEventQueue(nodeExpired);
        }
    }
    
    @Override
    protected void ProcessNewStreamObj(Instance inst)
    {                
        if (bShowProgress) ShowProgress("Processed " + (objId-1) + " stream objects.");       
        // PrintInstance(inst);
        
        double[] values = getInstanceValues(inst);
        StreamObj obj = new StreamObj(values);
        
        if (bTrace) Println("\n- - - - - - - - - - - -\n");

        // create new ISB node
        ISBNode nodeNew = new ISBNode(inst, obj, objId);
        if (bTrace) { Print("New node: "); PrintNode(nodeNew); }
        
        objId++; // update object identifier (slide window)
        
        AddNode(nodeNew); // add nodeNew to window
        if (bTrace) PrintWindow();
        
        ProcessNewNode(nodeNew, true);
        ProcessExpiredNode(GetExpiredNode());
        
        if (bTrace) {
            Print("Micro-clusters: "); PrintMCSet(setMC);
            PrintOutliers();
            PrintPD();
        }
        // DIAG ONLY -- DELETE
        System.out.println("---------------------- MCODmod1 ----------------------");
        System.out.println("DIAG - Total Exact MCs count: " + diagExactMCCount);
        System.out.println("DIAG - Total Approx MCs count: " + diagApproxMCCount);
        System.out.println("DIAG - Total Discarded MCs: " + diagDiscardedMCCount);
        System.out.println("DIAG - Total Points added to MCs: " + diagTotalPointsAddedToMC);
        System.out.println("DIAG - Total -ACTIVE- MCs: " + diagTotalActiveMCCount);
        System.out.println("DIAG - Total -ACTIVE- PD List Population: " + diagPDListPopulation);
        System.out.println("-------------------------------------------------------");
    }
}
