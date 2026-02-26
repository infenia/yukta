import * as d3 from 'd3';
import ELK from 'elkjs/lib/elk.bundled.js';

export default function dagComponent(nodesData, edgesData, pluginDetails) {
    const elk = new ELK();

    return {
        nodes: nodesData,
        edges: edgesData,
        plugins: pluginDetails,
        async init() {
            this.$nextTick(async () => {
                await this.render();
            });
            window.addEventListener('resize', () => this.render());
        },
        async render() {
            const svg = d3.select(this.$refs.dagSvg);
            const width = this.$refs.dagSvg.clientWidth || 600;
            const height = this.$refs.dagSvg.clientHeight || 400;
            svg.selectAll("*").remove();

            if (this.nodes.length === 0) return;

            const elkGraph = {
                id: "root",
                layoutOptions: {
                    'elk.algorithm': 'layered',
                    'elk.direction': 'RIGHT',
                    'elk.spacing.nodeNode': '60',
                    'elk.layered.spacing.nodeNodeLayered': '120'
                },
                children: this.nodes.map(n => {
                    const details = this.plugins[n.type];
                    return {
                        id: n.id,
                        width: details?.uiDesign?.width || 120,
                        height: details?.uiDesign?.height || 40,
                        type: n.type
                    };
                }),
                edges: this.edges.map((e, i) => ({
                    id: `e${i}`,
                    sources: [e.source],
                    targets: [e.target],
                    sourcePort: e.sourcePort
                }))
            };

            const layoutedGraph = await elk.layout(elkGraph);

            const container = svg.append("g");
            const line = d3.line().x(d => d.x).y(d => d.y).curve(d3.curveBasis);

            // Render Nodes
            layoutedGraph.children.forEach(n => {
                const details = this.plugins[n.type];
                const design = details?.uiDesign;

                const grp = container.append("g")
                    .attr("transform", `translate(${n.x}, ${n.y})`)
                    .attr("data-node-id", n.id);

                if (design) {
                    // Base shell
                    grp.append("rect")
                        .attr("width", n.width)
                        .attr("height", n.height)
                        .attr("rx", 16)
                        .attr("fill", "white")
                        .attr("stroke", "#e2e8f0")
                        .attr("stroke-width", 1)
                        .attr("class", "shadow-sm");

                    // Inject HTML with extra space for ports
                    const foPadding = 40;
                    const fo = grp.append("foreignObject")
                        .attr("x", -foPadding/2)
                        .attr("y", -foPadding/2)
                        .attr("width", n.width + foPadding)
                        .attr("height", n.height + foPadding);

                    let html = design.html;
                    html = html.replace(/{{nodeId}}/g, n.id);
                    html = html.replace(/{{type}}/g, n.type);

                    fo.append("xhtml:div")
                        .attr("style", "width:100%; height:100%; pointer-events:none; display: flex; align-items: center; justify-content: center;")
                        .html('<div style="width:' + n.width + 'px; height:' + n.height + 'px; position: relative; overflow: visible;">' + html + '</div>');
                } else {
                    grp.append("rect")
                        .attr("width", n.width)
                        .attr("height", n.height)
                        .attr("rx", 12)
                        .attr("fill", "#3c83f6")
                        .attr("stroke", "white")
                        .attr("stroke-width", 2)
                        .attr("class", "shadow-lg shadow-blue-500/20");

                    grp.append("text")
                        .attr("x", n.width/2)
                        .attr("y", n.height/2 + 4)
                        .attr("text-anchor", "middle")
                        .attr("fill", "white")
                        .attr("font-family", "Space Grotesk")
                        .attr("font-size", "10px")
                        .attr("font-weight", "700")
                        .attr("class", "uppercase tracking-widest")
                        .text(n.id);
                }
            });

            // Calculate Scale & Offset
            const bbox = container.node().getBBox();
            const scale = Math.min(width / (bbox.width + 80), height / (bbox.height + 80), 1);
            const xOffset = (width - bbox.width * scale) / 2 - bbox.x * scale;
            const yOffset = (height - bbox.height * scale) / 2 - bbox.y * scale;
            container.attr("transform", `translate(${xOffset}, ${yOffset}) scale(${scale})`);

            // Render Edges
            layoutedGraph.edges.forEach(e => {
                let points = [];
                if (e.sections && e.sections.length > 0) {
                    const section = e.sections[0];
                    points.push(section.startPoint);
                    if (section.bendPoints) {
                        points.push(...section.bendPoints);
                    }
                    points.push(section.endPoint);
                }

                if (points.length === 0) return;

                // Handle custom ports if necessary (though ELK handles ports too if defined)
                const sourceId = e.sources[0];
                const sourcePort = e.sourcePort;
                if (sourcePort && sourcePort !== "null") {
                    const nodeGrp = svg.select(`[data-node-id="${sourceId}"]`).node();
                    const portEl = nodeGrp?.querySelector(`.jagratha-port[data-port-name="${sourcePort}"]`);

                    if (portEl && nodeGrp) {
                        const nodeRect = nodeGrp.getBoundingClientRect();
                        const portRect = portEl.getBoundingClientRect();

                        const sourceNode = layoutedGraph.children.find(c => c.id === sourceId);
                        const relX = (portRect.left + portRect.width/2 - nodeRect.left) / scale;
                        const relY = (portRect.top + portRect.height/2 - nodeRect.top) / scale;

                        points[0] = {
                            x: sourceNode.x + relX,
                            y: sourceNode.y + relY
                        };
                    }
                }

                container.append("path")
                    .attr("d", line(points))
                    .attr("fill", "none")
                    .attr("stroke", "#3c83f6")
                    .attr("stroke-width", 2)
                    .attr("opacity", 0.6)
                    .attr("marker-end", "url(#arrowhead)");
            });

            // Arrowhead marker
            svg.append("defs").append("marker")
                .attr("id", "arrowhead")
                .attr("viewBox", "0 0 10 10")
                .attr("refX", 9)
                .attr("refY", 5)
                .attr("markerWidth", 6)
                .attr("markerHeight", 6)
                .attr("orient", "auto")
                .append("path")
                .attr("d", "M 0 0 L 10 5 L 0 10 z")
                .attr("fill", "#3c83f6")
                .attr("opacity", 0.2);
        }
    }
}
