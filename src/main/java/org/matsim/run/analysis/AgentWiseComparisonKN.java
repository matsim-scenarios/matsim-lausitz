package org.matsim.run.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.selection.Selection;

import java.io.IOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.*;

import static org.matsim.application.ApplicationUtils.globFile;
import static org.matsim.core.router.TripStructureUtils.StageActivityHandling.*;
import static org.matsim.run.analysis.HeadersKN.deltaOf;
import static org.matsim.run.analysis.HeadersKN.keyTwoOf;

@CommandLine.Command(name = "monetary-utility", description = "List and compare fare, dailyRefund and utility values for agents in base and policy case.")
public class AgentWiseComparisonKN implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger( AgentWiseComparisonKN.class );

	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which analysis should be performed.")
	private List<Path> inputPaths;

	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path baseCasePath;

	@CommandLine.Option(names = "--prefix", description = "Prefix for filtered events output file, optional. This can be a list of multiple prefixes. " +
		"Number of prefixes has to be equal to number of inputPaths and the list of prefixes has to have the same order as inputPaths.", split = ",")
	private List<String> prefixList = new ArrayList<>();

	public static void main(String[] args) {
		new AgentWiseComparisonKN().execute(args );
	}

	@Override
	public Integer call() throws Exception {
		if (!prefixList.isEmpty() && prefixList.size() != inputPaths.size()) {
			log.error("The numbers of prefixes {} and input paths {} do not match.", prefixList, inputPaths);
			return 2;
		}

		List<String> eventsFiles = new ArrayList<>();

		if (!prefixList.isEmpty()) {
			for (String prefix : prefixList) {
				eventsFiles.add("*" + prefix + "output_events_filtered.xml.gz");
			}
		} else {
			eventsFiles.add("*output_events.xml.gz");
		}

		String basePopulationFilename = globFile( baseCasePath, "*output_experienced_plans.xml.gz" ).toString();
		String baseConfigFilename = globFile( baseCasePath, "*output_config.xml" ).toString();

		Config config = ConfigUtils.loadConfig(baseConfigFilename);

		final Table baseTable = processPopulationAndGenerateTable( basePopulationFilename, config );

		for (String pattern : eventsFiles) {
			handleEventsfile( baseCasePath, pattern, baseTable );
		}

		Table ptTable = baseTable.where( baseTable.stringColumn( HeadersKN.modeSeq ).containsString( "pt" ) );
		System.out.println( ptTable );

		if (eventsFiles.size() == 1) {
			String pattern = eventsFiles.getFirst();
			for (Path inputPath : inputPaths) {
				readPolicyDataAndCompare(inputPath, pattern, baseTable );
			}
		} else {
			for (String pattern : eventsFiles) {
				readPolicyDataAndCompare( inputPaths.get(eventsFiles.indexOf(pattern ) ), pattern, baseTable );
			}
		}
		return 0;
	}
	private static void handleEventsfile( Path baseCasePath, String pattern, Table baseTable ){
		String baseEventsFile = globFile( baseCasePath, pattern ).toString();

		EventsManager events = EventsUtils.createEventsManager();
		events.addHandler( new MyMoneyEventHandler( baseTable ) );
		events.initProcessing();

		MatsimEventsReader baseReader = new MatsimEventsReader( events );
		baseReader.readFile( baseEventsFile );
		events.finishProcessing();
	}
	@NotNull private static Table processPopulationAndGenerateTable( String basePopulationFileName, Config config ){
		Population basePopulation = PopulationUtils.readPopulation( basePopulationFileName );
		log.warn( "popSize={}",basePopulation.getPersons().size());
		cleanPopulation( basePopulation );
		log.warn( "popSize={}",basePopulation.getPersons().size());

		Table table = Table.create( StringColumn.create( HeadersKN.personId )
				, DoubleColumn.create( HeadersKN.income )
				, DoubleColumn.create( HeadersKN.score )
				, DoubleColumn.create( HeadersKN.ttime )
				, DoubleColumn.create( HeadersKN.money )
				, DoubleColumn.create( HeadersKN.ascs )
				, StringColumn.create( HeadersKN.modeSeq )
				, StringColumn.create( HeadersKN.actSeq )
								  );
		for( Person person : basePopulation.getPersons().values() ){
			table.stringColumn( HeadersKN.personId ).append( person.getId().toString() );
			table.doubleColumn( HeadersKN.income ).append( PersonUtils.getIncome( person ) );
		}
		double avIncome = table.doubleColumn( HeadersKN.income ).mean();
		table.addColumns( table.doubleColumn( HeadersKN.income )
							   .multiply( config.scoring().getMarginalUtilityOfMoney() / avIncome ).setName( HeadersKN.utlOfMoney ) );

		log.info("##############################################################################################################################");
		log.info("Mean monthly income of person agents: {}€", avIncome);
		log.info("##############################################################################################################################");


		MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
		for( Person person : basePopulation.getPersons().values() ){

			// yyyyyy the following is very slow; presumably, we better to full column operations and add the monetized columns!!
//			int row = table.stringColumn( HeadersKN.personId ).isEqualTo( person.getId().toString() ).iterator().nextInt();
//			double margUtlOfMoney = table.doubleColumn( HeadersKN.utlOfMoney ).get( row );

			table.doubleColumn( HeadersKN.score ).append( person.getSelectedPlan().getScore() );
			{
				double sumTtime = 0.;
				double sumMoney = 0.;
				double dailyCarMoney = 0.;
				double sumAscs = 0.;
				for( Leg leg : TripStructureUtils.getLegs( person.getSelectedPlan() ) ){
					sumTtime += leg.getTravelTime().seconds();
					if ( "car".equals( leg.getMode() ) ) {
						sumMoney += leg.getRoute().getDistance() * config.scoring().getModes().get( "car" ).getMonetaryDistanceRate();
						dailyCarMoney = config.scoring().getModes().get( "car").getDailyMonetaryConstant();
					}
					final ScoringConfigGroup.ModeParams modeParams = config.scoring().getModes().get( leg.getMode() );
					if ( modeParams==null ) {
						log.warn("modeParams=null for mode={}", leg.getMode() );
					}
					sumAscs += modeParams.getConstant() ;
				}
				table.doubleColumn( HeadersKN.ttime ).append( sumTtime/3600*6  );
				table.doubleColumn( HeadersKN.money ).append( sumMoney + dailyCarMoney );
				table.doubleColumn( HeadersKN.ascs ).append( sumAscs );
			}
			{
				List<String> modes = new ArrayList<>();
				for( TripStructureUtils.Trip trip : TripStructureUtils.getTrips( person.getSelectedPlan() ) ){
					mainModeIdentifier.identifyMainMode( trip.getTripElements() );
					modes.add( mainModeIdentifier.identifyMainMode( trip.getTripElements() ) );
				}
				table.stringColumn( HeadersKN.modeSeq ).append( String.join( "--", modes ) ) ;
			}
			{
				List<String> acts = new ArrayList<>();
				for( Activity activity : TripStructureUtils.getActivities( person.getSelectedPlan(), ExcludeStageActivities ) ){
					acts.add( activity.getType().substring( 0,4 ) );
				}
				table.stringColumn( HeadersKN.actSeq ).append( String.join( "|", acts ) );
			}
		}

		System.out.println( table );

		return table;
	}
	private static void readPolicyDataAndCompare( Path inputPath, String pattern, Table tableBase ) throws IOException {
		log.info("Running on {}", inputPath);

		String baseConfigFilename = globFile( inputPath, "*output_config.xml" ).toString();
		Config config = ConfigUtils.loadConfig(baseConfigFilename);

		String populationFileName = globFile(inputPath, "*output_experienced_plans.xml.gz").toString();
		Table tablePolicy = processPopulationAndGenerateTable( populationFileName, config );


		handleEventsfile( inputPath, pattern, tablePolicy );


		// Compute overlapping columns (excluding join keys)
		Set<String> leftCols = new HashSet<>(tableBase.columnNames());
		Set<String> rightCols = new HashSet<>(tablePolicy.columnNames());

		leftCols.remove( HeadersKN.personId );
		rightCols.remove( HeadersKN.personId );

		Set<String> duplicates = new HashSet<>(leftCols);
		duplicates.retainAll(rightCols);

		// Rename duplicates in right-hand table
		for (String dup : duplicates) {
			tablePolicy.column( dup ).setName( keyTwoOf(  dup ) );
		}

		Table filteredTable = tablePolicy.where( tablePolicy.stringColumn( keyTwoOf(HeadersKN.modeSeq) ).containsString( "drt" ) );
//		Table filteredTable = tablePolicy;

		Table joinedTable = tableBase.joinOn( HeadersKN.personId ).inner( filteredTable );

		Table deltaTable = Table.create( joinedTable.column( HeadersKN.personId )
				,joinedTable.column( HeadersKN.utlOfMoney )
//				, joinedTable.column( HeadersKN.score )
				, deltaColumn( joinedTable, HeadersKN.score )
//				, joinedTable.column( HeadersKN.ttime )
				, deltaColumn( joinedTable, HeadersKN.ttime )
//				, joinedTable.column( HeadersKN.money )
				, deltaColumn( joinedTable, HeadersKN.money )
//				, joinedTable.column( HeadersKN.ascs )
				, deltaColumn( joinedTable, HeadersKN.ascs )
				, joinedTable.column( HeadersKN.modeSeq )
				, joinedTable.column( keyTwoOf( HeadersKN.modeSeq ) )
//				, joinedTable.column( HeadersKN.actSeq )
//				, joinedTable.column( keyTwoOf( HeadersKN.actSeq ) )
									   );

		log.warn("d_score_mean={}, d_score_ttime_mean={}, d_money_mean={}, d_asc_mean={}"
				, deltaTable.doubleColumn( deltaOf( HeadersKN.score )).mean()
				, deltaTable.doubleColumn( deltaOf( HeadersKN.ttime ) ).mean() / 3600 * 6
				, deltaTable.doubleColumn( deltaOf( HeadersKN.money ) ).mean()
				, deltaTable.doubleColumn( deltaOf( HeadersKN.ascs) ).mean()
				);

		Table sortedTable = deltaTable.sortOn( deltaOf( HeadersKN.score ) );

		NumberFormat format = NumberFormat.getNumberInstance( Locale.GERMANY);
		format.setMaximumFractionDigits(2);
		format.setMinimumFractionDigits( 2 );
//		sortedTable.numberColumn(HeadersKN.score ).setPrintFormatter(format, "n/a");
//		sortedTable.numberColumn(HeadersKN.money ).setPrintFormatter(format, "n/a");
		sortedTable.numberColumn( deltaOf(HeadersKN.score) ).setPrintFormatter(format, "n/a");
		sortedTable.numberColumn( deltaOf(HeadersKN.money) ).setPrintFormatter(format, "n/a");

		System.out.println( sortedTable );
		System.exit(-1);

	}
	private static DoubleColumn deltaColumn( Table joinedTable, String key ){
		return joinedTable.doubleColumn( keyTwoOf( key ) ).subtract( joinedTable.doubleColumn( key )).setName( HeadersKN.deltaOf( key ) ) ;
	}

	private static boolean isTestPerson( Id<Person> personId ){
		return "297374".equals( personId.toString() );
	}

	private static void cleanPopulation( Population basePopulation ){
		List<Id<Person>> personsToRemove = new ArrayList<>();
		for( Person person : basePopulation.getPersons().values() ){
			Id<Person> personId = person.getId();
			if ( personId.toString().contains("goods") || personId.toString().contains("commercial") || personId.toString().contains("freight")  ) {
				personsToRemove.add( person.getId() );
			}
		}
		for( Id<Person> personId : personsToRemove ){
			basePopulation.removePerson( personId );
		}
	}

	private static class MyMoneyEventHandler implements PersonMoneyEventHandler{
		private final Table table;
		public MyMoneyEventHandler( Table table ){
			this.table = table;
		}
		@Override public void handleEvent( PersonMoneyEvent event ){
			int row = table.stringColumn( HeadersKN.personId ).isEqualTo( event.getPersonId().toString() ).iterator().nextInt();
			final DoubleColumn moneyColumn = table.doubleColumn( HeadersKN.money );
			double prevAmount = moneyColumn.get( row );
			moneyColumn.set( row, prevAmount + event.getAmount() );
		}
	}



}
