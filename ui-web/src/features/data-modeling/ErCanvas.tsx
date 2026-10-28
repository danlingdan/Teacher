import { useEffect, useCallback } from "react";
import {
  Background,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type EdgeTypes,
  type NodeTypes,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { AttributeNode, EntityNode, IsaNode, RelationshipNode } from "./ErNodes";
import { ErStraightEdge } from "./ErEdges";
import type { ErFlowElements } from "./erModel";

// v3.11.0 ERM-5：React Flow 画布封装。nodeTypes/edgeTypes 必须定义在组件外，
// 否则每次渲染传入新对象会触发 React Flow 告警并造成重挂载。

const nodeTypes: NodeTypes = {
  entity: EntityNode,
  attribute: AttributeNode,
  relationship: RelationshipNode,
  isa: IsaNode,
};
// 以自定义组件覆盖内建 "straight" 边：直线裁剪到形状边界 + 双线 + 基数标注。
const edgeTypes: EdgeTypes = { straight: ErStraightEdge };

export type ErCanvasApi = {
  fitView: () => void;
};

function ErFlowInner({ elements, onReady }: { elements: ErFlowElements; onReady?: (api: ErCanvasApi) => void }) {
  const [nodes, setNodes, onNodesChange] = useNodesState(elements.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(elements.edges);
  const { fitView } = useReactFlow();
  const fit = useCallback(() => void fitView({ padding: 0.12, duration: 180 }), [fitView]);
  // 数据变化（重新生成）时重置图元并回到适应视图；用户此后可自由拖拽/缩放。
  useEffect(() => {
    setNodes(elements.nodes);
    setEdges(elements.edges);
    const frame = requestAnimationFrame(fit);
    return () => cancelAnimationFrame(frame);
  }, [elements, setNodes, setEdges, fit]);
  useEffect(() => {
    onReady?.({ fitView: fit });
  }, [onReady, fit]);

  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      nodesConnectable={false}
      edgesFocusable={false}
      // 生成结果是权威数据：不允许键盘删除图元，只保留拖拽微调。
      deleteKeyCode={null}
      minZoom={0.15}
      maxZoom={2.5}
      fitView
      className="er-flow"
      proOptions={{ hideAttribution: true }}
      aria-label="E-R 图画布"
    >
      <Background className="er-background" gap={22} size={1.4} />
      <Controls showInteractive={false} position="bottom-right" />
    </ReactFlow>
  );
}

export default function ErCanvas({ elements, onReady }: { elements: ErFlowElements; onReady?: (api: ErCanvasApi) => void }) {
  return (
    <ReactFlowProvider>
      <ErFlowInner elements={elements} onReady={onReady} />
    </ReactFlowProvider>
  );
}
