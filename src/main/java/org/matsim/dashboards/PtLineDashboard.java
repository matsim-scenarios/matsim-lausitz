package org.matsim.dashboards;

import org.matsim.run.analysis.PtLineAnalysis;
import org.matsim.run.scenarios.LausitzScenario;
import org.matsim.simwrapper.Dashboard;
import org.matsim.simwrapper.Header;
import org.matsim.simwrapper.Layout;
import org.matsim.simwrapper.viz.*;
import tech.tablesaw.plotly.traces.BarTrace;

import java.util.ArrayList;
import java.util.List;

import static org.matsim.run.scenarios.LausitzScenario.SLASH;

/**
 * Shows information about an optional policy case, which implements a pt line between Cottbus and Hoyerswerda.
 * It also compares the agents and their trips using the new pt line with their respective trips in the base case.
 */
public class PtLineDashboard implements Dashboard {
	private final String basePath;
	private static final String SHARE = "share";
	private static final String ABSOLUTE = "Count [person]";
	private static final String INCOME_GROUP = "incomeGroup";
	private static final String AGE_GROUP = "ageGroup";
	private static final String DESCRIPTION = "... in base and policy case";

	public PtLineDashboard(String basePath) {
		if (!basePath.endsWith(SLASH)) {
			basePath += SLASH;
		}
		this.basePath = basePath;
	}

	@Override
	public void configure(Header header, Layout layout) {
		header.title = "Pt Line Dashboard";
		header.description = "Shows statistics about agents, who used the newly implemented pt line between Cottbus and Hoyerswerda " +
			"and compares to the trips of those agents in the base case.";

		String[] args = new ArrayList<>(List.of("--base-path", basePath)).toArray(new String[0]);

		layout.row("first")
			.el(Tile.class, (viz, data) -> {
			viz.dataset = data.compute(PtLineAnalysis.class, "mean_travel_stats.csv", args);
			viz.height = 0.1;
		});

		layout.row("income")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per income group";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_income_groups.csv", args);
				viz.x = INCOME_GROUP;
				viz.xAxisName = INCOME_GROUP;
				viz.yAxisName = SHARE;
				viz.columns = List.of(SHARE);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per income group";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_income_groups.csv", args);
				viz.x = INCOME_GROUP;
				viz.xAxisName = INCOME_GROUP;
				viz.yAxisName = ABSOLUTE;
				viz.columns = List.of(ABSOLUTE);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "General income distribution";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "all_persons_income_groups.csv", args);
				viz.x = INCOME_GROUP;
				viz.xAxisName = INCOME_GROUP;
				viz.yAxisName = ABSOLUTE;
				viz.columns = List.of(ABSOLUTE);
			});
		layout.row("score")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Mean score per income group";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_mean_score_per_income_group.csv", args);
				viz.x = INCOME_GROUP;
				viz.xAxisName = INCOME_GROUP;
				viz.yAxisName = "mean score";
				viz.columns = List.of("mean_score_base", "mean_score_policy");});

		createAgePlots(layout, args);

		layout.row("third")
			.el(Plotly.class, (viz, data) -> {
				viz.title = "Modal split (base case)";
				viz.description = "Shows mode of agents in base case, which used the new pt line in the policy case.";

				viz.layout = tech.tablesaw.plotly.components.Layout.builder()
					.barMode(tech.tablesaw.plotly.components.Layout.BarMode.STACK)
					.build();

				Plotly.DataSet ds = viz.addDataset(data.compute(PtLineAnalysis.class, "pt_persons_base_modal_share.csv", args))
					.constant("source", "Base Case Mode")
					.aggregate(List.of("main_mode"), SHARE, Plotly.AggrFunc.SUM);

				viz.mergeDatasets = true;
				viz.addTrace(BarTrace.builder(Plotly.OBJ_INPUT, Plotly.INPUT).orientation(BarTrace.Orientation.HORIZONTAL).build(),
					ds.mapping()
						.name("main_mode")
						.y("source")
						.x(SHARE)
				);
			})
			.el(Hexagons.class, (viz, data) -> {

				viz.title = "Pt line agents home locations";
				viz.center = data.context().getCenter();
				viz.zoom = data.context().mapZoomLevel;
				viz.height = 7.5;
				viz.width = 2.0;

				viz.file = data.compute(PtLineAnalysis.class, "pt_persons_home_locations.csv");
				viz.projection = LausitzScenario.CRS;
				viz.addAggregation("home locations", "person", "home_x", "home_y");
			});

		createTableLayouts(layout);
	}

	private static void createAgePlots(Layout layout, String[] args) {
		layout.row("age")
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per age group";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_age_groups.csv", args);
				viz.x = AGE_GROUP;
				viz.xAxisName = AGE_GROUP;
				viz.yAxisName = SHARE;
				viz.columns = List.of(SHARE);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "Agents per age group";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_age_groups.csv", args);
				viz.x = AGE_GROUP;
				viz.xAxisName = AGE_GROUP;
				viz.yAxisName = ABSOLUTE;
				viz.columns = List.of(ABSOLUTE);
			})
			.el(Bar.class, (viz, data) -> {
				viz.title = "General age distribution";
				viz.stacked = false;
				viz.dataset = data.compute(PtLineAnalysis.class, "all_persons_age_groups.csv", args);
				viz.x = AGE_GROUP;
				viz.xAxisName = AGE_GROUP;
				viz.yAxisName = ABSOLUTE;
				viz.columns = List.of(ABSOLUTE);
			});
	}

	private static void createTableLayouts(Layout layout) {
		layout.row("fourth")
			.el(Table.class, (viz, data) -> {
				viz.title = "Executed scores";
				viz.description = DESCRIPTION;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_executed_score.csv");
				viz.showAllRows = true;
			})
			.el(Table.class, (viz, data) -> {
				viz.title = "Travel times";
				viz.description = DESCRIPTION;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_trav_time.csv");
				viz.showAllRows = true;
			})
			.el(Table.class, (viz, data) -> {
				viz.title = "Travel distances";
				viz.description = DESCRIPTION;
				viz.dataset = data.compute(PtLineAnalysis.class, "pt_persons_traveled_distance.csv");
				viz.showAllRows = true;
			});
	}
}
