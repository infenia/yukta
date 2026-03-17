import * as d3 from 'd3';
import ELK from 'elkjs/lib/elk.bundled.js';

export default function dagComponent(nodesData, edgesData, pluginDetails) {
    const elk = new ELK();

    return {
        nodes: nodesData,
        edges: edgesData,
        plugins: pluginDetails,
        isFullscreen: false,
        zoomLevel: 1,
        panX: 0,
        panY: 0,
        isDragging: false,
        dragStart: { x: 0, y: 0 },
        tooltip: {
            show: false,
            text: '',
            x: 0,
            y: 0
        },

        async init() {
            this.$nextTick(async () => {
                await this.render();
                this.setupInteractions();
            });
            window.addEventListener('resize', () => this.render());
        },

        setupInteractions() {
            const svg = d3.select(this.$refs.dagSvg);
            if (!svg.node()) return;

            // Zoom behavior
            const zoom = d3.zoom()
                .on('zoom', (event) => {
                    d3.select(this.$refs.dagSvg).select('g').attr('transform', event.transform);
                    this.zoomLevel = event.transform.k;
                    this.panX = event.transform.x;
                    this.panY = event.transform.y;
                });

            svg.call(zoom);

            // Reset zoom on double-click
            svg.on('dblclick.zoom', null);
        },

        zoomIn() {
            this.zoomLevel = Math.min(this.zoomLevel + 0.2, 3);
            this.applyTransform();
        },

        zoomOut() {
            this.zoomLevel = Math.max(this.zoomLevel - 0.2, 0.5);
            this.applyTransform();
        },

        resetZoom() {
            this.zoomLevel = 1;
            this.panX = 0;
            this.panY = 0;
            this.applyTransform();
        },

        applyTransform() {
            const svg = d3.select(this.$refs.dagSvg);
            const g = svg.select('g');
            g.attr('transform', `translate(${this.panX}, ${this.panY}) scale(${this.zoomLevel})`);
        },

        toggleFullscreen() {
            this.isFullscreen = !this.isFullscreen;
            this.$nextTick(async () => {
                if (this.isFullscreen && this.$refs.dagSvgFullscreen) {
                    await this.renderFullscreen();
                }
            });
        },

        async renderFullscreen() {
            const svg = d3.select(this.$refs.dagSvgFullscreen);
            await this.renderDAG(svg, window.innerWidth, window.innerHeight);
        },

        async render() {
            const svg = d3.select(this.$refs.dagSvg);
            const width = this.$refs.dagSvg.clientWidth || 600;
            const height = this.$refs.dagSvg.clientHeight || 400;
            await this.renderDAG(svg, width, height);
            this.setupInteractions();
        },

        async renderDAG(svg, width, height) {
            svg.selectAll("*").remove();

            if (!this.nodes || this.nodes.length === 0) return;

            const elkGraph = {
                id: "root",
                layoutOptions: {
                    'elk.algorithm': 'layered',
                    'elk.direction': 'RIGHT',
                    'elk.spacing.nodeNode': '80',
                    'elk.layered.spacing.nodeNodeLayered': '140',
                    'elk.edgeRouting': 'SPLINES'
                },
                children: this.nodes.map(n => {
                    const details = this.plugins[n.type];
                    return {
                        id: n.id,
                        width: details?.uiDesign?.width || 140,
                        height: details?.uiDesign?.height || 80,
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

            // Add defs for markers
            const defs = svg.append("defs");
            const createMarker = (id, highlight = false) => {
                const marker = defs.append("marker")
                    .attr("id", id)
                    .attr("viewBox", "0 0 10 10")
                    .attr("refX", 9)
                    .attr("refY", 5)
                    .attr("markerWidth", 5)
                    .attr("markerHeight", 5)
                    .attr("orient", "auto")
                    .attr("markerUnits", "strokeWidth");

                marker.append("path")
                    .attr("d", "M 0 0 L 10 5 L 0 10 z");

                return marker;
            };

            createMarker("arrowhead");
            createMarker("arrowhead-highlight", true);

            const container = svg.append("g");
            const line = d3.line().x(d => d.x).y(d => d.y).curve(d3.curveBasis);

            // Render Edges First (so they are behind nodes)
            const edgePaths = layoutedGraph.edges.map(e => {
                let points = [];
                if (e.sections && e.sections.length > 0) {
                    const section = e.sections[0];
                    points.push(section.startPoint);
                    if (section.bendPoints) {
                        points.push(...section.bendPoints);
                    }
                    points.push(section.endPoint);
                }

                if (points.length === 0) return null;

                const path = container.append("path")
                    .attr("d", line(points))
                    .attr("class", "dag-line")
                    .attr("data-source", e.sources[0])
                    .attr("data-target", e.targets[0])
                    .attr("marker-end", "url(#arrowhead)");

                return { edge: e, path: path };
            }).filter(p => p !== null);

            // Render Nodes
            layoutedGraph.children.forEach(n => {
                const details = this.plugins[n.type];
                const design = details?.uiDesign;

                const grp = container.append("g")
                    .attr("transform", `translate(${n.x}, ${n.y})`)
                    .attr("class", "dag-node")
                    .attr("data-node-id", n.id)
                    .on("mouseenter", () => {
                        // Highlight edges connected to this node
                        container.selectAll(".dag-line")
                            .filter(function() {
                                return d3.select(this).attr("data-source") === n.id ||
                                       d3.select(this).attr("data-target") === n.id;
                            })
                            .classed("highlight", true)
                            .attr("marker-end", "url(#arrowhead-highlight)");
                    })
                    .on("mouseleave", () => {
                        // Remove highlight
                        container.selectAll(".dag-line")
                            .classed("highlight", false)
                            .attr("marker-end", "url(#arrowhead)");
                    });

                // Standard container for all nodes
                grp.append("rect")
                    .attr("width", n.width)
                    .attr("height", n.height)
                    .attr("rx", 12);

                const foPadding = 40;
                const fo = grp.append("foreignObject")
                    .attr("x", -foPadding/2)
                    .attr("y", -foPadding/2)
                    .attr("width", n.width + foPadding)
                    .attr("height", n.height + foPadding);

                let html = design?.html;
                if (!html) {
                    // Default Design
                    html = `
                        <div class="flex items-center w-full h-full bg-slate-50 border-2 border-slate-200 rounded-xl px-4 gap-3">
                            <div class="flex-shrink-0 w-8 h-8 bg-slate-100 rounded-lg flex items-center justify-center text-slate-500">
                                <span class="material-symbols-outlined text-xl">extension</span>
                            </div>
                            <div class="flex flex-col min-w-0">
                                <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">{{type}}</div>
                                <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                            </div>
                        </div>
                    `;
                }

                html = html.replace(/{{nodeId}}/g, n.id);
                html = html.replace(/{{type}}/g, n.type);

                fo.append("xhtml:div")
                    .attr("style", "width:100%; height:100%; pointer-events:none; display: flex; align-items: center; justify-content: center;")
                    .html('<div style="width:' + n.width + 'px; height:' + n.height + 'px; position: relative; overflow: visible;">' + html + '</div>');

                // Tooltip interaction
                grp.on("mousemove", (event) => {
                    this.tooltip = {
                        show: true,
                        text: n.id,
                        x: event.clientX + 10,
                        y: event.clientY + 10
                    };
                }).on("mouseleave", () => {
                    this.tooltip.show = false;
                    // Also trigger the existing highlight cleanup
                    container.selectAll(".dag-line")
                        .classed("highlight", false)
                        .attr("marker-end", "url(#arrowhead)");
                });
            });

            // Calculate Scale & Offset
            const bbox = container.node().getBBox();
            const scale = Math.min(width / (bbox.width + 80), height / (bbox.height + 80), 1);
            const xOffset = (width - bbox.width * scale) / 2 - bbox.x * scale;
            const yOffset = (height - bbox.height * scale) / 2 - bbox.y * scale;
            container.attr("transform", `translate(${xOffset}, ${yOffset}) scale(${scale})`);
        }
    }
}
