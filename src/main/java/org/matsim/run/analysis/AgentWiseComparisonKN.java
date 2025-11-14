package org.matsim.run.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
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
import tech.tablesaw.columns.Column;

import java.io.IOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.*;

import static org.matsim.api.core.v01.TransportMode.*;
import static org.matsim.application.ApplicationUtils.globFile;
import static org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import static org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import static org.matsim.core.router.TripStructureUtils.StageActivityHandling.ExcludeStageActivities;

@CommandLine.Command(name = "monetary-utility", description = "List and compare fare, dailyRefund and utility values for agents in base and policy case.")
public class AgentWiseComparisonKN implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger( AgentWiseComparisonKN.class );
	public static final String KN_MONEY = "knMoney";

	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which analysis should be performed.")
	private List<Path> inputPaths;

	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path baseCasePath;

	@CommandLine.Option(names = "--prefix", description = "Prefix for filtered events output file, optional. This can be a list of multiple prefixes. " +
		"Number of prefixes has to be equal to number of inputPaths and the list of prefixes has to have the same order as inputPaths.", split = ",")
	private List<String> prefixList = new ArrayList<>();
	// (yy not totally obvious to me how this works.  Different prefixes for different input paths? kai, nov'25)

	NumberFormat format = NumberFormat.getNumberInstance( Locale.GERMANY);


	public static void main(String[] args) {
		new AgentWiseComparisonKN().execute(args );
	}

	@Override
	public Integer call() throws Exception {
		format.setMaximumFractionDigits(2);
		format.setMinimumFractionDigits( 2 );

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
		String baseConfigFilename = globFile( baseCasePath, "*output_config_reduced.xml" ).toString();
		// (The reduced config has fewer problems with newly introduced config params.)

		Config config = ConfigUtils.loadConfig(baseConfigFilename);
		config.controller().setOverwriteFileSetting( OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists );

		config.scoring().addActivityParams( new ActivityParams( TripStructureUtils.createStageActivityType( car ) ).setScoringThisActivityAtAll( false ) );
		config.scoring().addActivityParams( new ActivityParams( TripStructureUtils.createStageActivityType( bike ) ).setScoringThisActivityAtAll( false ) );
		config.scoring().addActivityParams( new ActivityParams( TripStructureUtils.createStageActivityType( walk ) ).setScoringThisActivityAtAll( false ) );
		config.scoring().addActivityParams( new ActivityParams( TripStructureUtils.createStageActivityType( pt ) ).setScoringThisActivityAtAll( false ) );


		Population basePopulation = PopulationUtils.readPopulation( basePopulationFilename );
		log.warn( "popSize={}",basePopulation.getPersons().size());
		cleanPopulation( basePopulation );
		log.warn( "popSize={}",basePopulation.getPersons().size());

		for (String pattern : eventsFiles) {
			handleEventsfile( baseCasePath, pattern, basePopulation );
		}

//		MutableScenario scenario = ScenarioUtils.createMutableScenario( config );
//		scenario.setPopulation( basePopulation );

//		com.google.inject.Injector injector = Injector.createMinimalMatsimInjector( config, scenario );
//		ScoringFunctionFactory factory = injector.getInstance( ScoringFunctionFactory.class );

//		ScoringFunction fct = factory.createNewScoringFunction( person );

		// yyyy up to here, we should only have the matsim data structures, since with those it is easier to add and remove.  Convesion to table should come afterwards.

		Table baseTable = generateTableFromPopulation( basePopulation, config );

		for( Column<?> column : baseTable.columns() ){
			if ( column instanceof DoubleColumn ) {
				((DoubleColumn) column).setPrintFormatter( format, "n/a" );
			}
		}

		final Table sortedTable = baseTable.sortDescendingOn( HeadersKN.SCORE );
		System.out.println( sortedTable );
//		log.info("===");
//		System.out.println( sortedTable
////									.where( sortedTable.doubleColumn( HeadersKN.ttime ).isBetweenExclusive( 0.,4. ) )
//									.first(1000 ).printAll() );
		System.exit(-1);

		{
			Table ptTable = baseTable.where( baseTable.stringColumn( HeadersKN.MODE_SEQ ).containsString( "pt" ) );
			log.info( ptTable );
		}
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
	private static void handleEventsfile( Path baseCasePath, String pattern, Population population ){
		String baseEventsFile = globFile( baseCasePath, pattern ).toString();

		double popSizeBefore = population.getPersons().size();

		EventsManager events = EventsUtils.createEventsManager();
		events.addHandler( new MyMoneyEventsHandler( population ) );
		events.addHandler( new MyStuckEventsHandler( population ) );
		events.initProcessing();

		MatsimEventsReader baseReader = new MatsimEventsReader( events );
		baseReader.readFile( baseEventsFile );
		events.finishProcessing();

		log.warn("popSize before={}; popSize after={}; ", popSizeBefore, population.getPersons().size() );

	}
	@NotNull private static Table generateTableFromPopulation( Population population, Config config ){

		Table table = Table.create( StringColumn.create( HeadersKN.PERSON_ID)
				, DoubleColumn.create( HeadersKN.INCOME)
				, DoubleColumn.create( HeadersKN.SCORE)
				, DoubleColumn.create( HeadersKN.TTIME)
				, DoubleColumn.create( HeadersKN.MONEY)
				, DoubleColumn.create( HeadersKN.ASCS)
				, StringColumn.create( HeadersKN.MODE_SEQ)
				, StringColumn.create( HeadersKN.ACT_SEQ)
								  );

		for( Person person : population.getPersons().values() ){
			table.stringColumn( HeadersKN.PERSON_ID).append( person.getId().toString() );
			table.doubleColumn( HeadersKN.INCOME).append( PersonUtils.getIncome( person ) );
		}
		double avIncome = table.doubleColumn( HeadersKN.INCOME).mean();
		log.warn("averageIncome={}", avIncome );
		table.addColumns( table.doubleColumn( HeadersKN.INCOME).reciprocal()
							   .multiply( avIncome / config.scoring().getMarginalUtilityOfMoney() ).setName( HeadersKN.UTL_OF_MONEY) );

		MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
		for( Person person : population.getPersons().values() ){

			// the following two lines are very slow; presumably, we better do full column operations and add the monetized columns separately.
//			int row = table.stringColumn( HeadersKN.personId ).isEqualTo( person.getId().toString() ).iterator().nextInt();
//			double margUtlOfMoney = table.doubleColumn( HeadersKN.utlOfMoney ).get( row );

			table.doubleColumn( HeadersKN.SCORE).append( person.getSelectedPlan().getScore() );

			double sumTtime = 0.;
			double sumMoney = 0.;
				Double moneyFromEvents = (Double) person.getAttributes().getAttribute( KN_MONEY );
				if ( moneyFromEvents!=null ) {
					sumMoney += moneyFromEvents ;
				};
			double dailyCarMoney = 0.;
			double sumAscs = 0.;
			for( Leg leg : TripStructureUtils.getLegs( person.getSelectedPlan() ) ){
				sumTtime += leg.getTravelTime().seconds();
				if ( "car".equals( leg.getMode() ) ) {
					sumMoney += leg.getRoute().getDistance() * config.scoring().getModes().get( "car" ).getMonetaryDistanceRate();
					dailyCarMoney = config.scoring().getModes().get( "car").getDailyMonetaryConstant();
						// (NOT += since we only want this once!)
				}
					final ModeParams modeParams = config.scoring().getModes().get( leg.getMode() );
				if ( modeParams==null ) {
						throw new RuntimeException("modeParams=null for mode=" + leg.getMode() );
				}
				sumAscs += modeParams.getConstant() ;
			}
			table.doubleColumn( HeadersKN.TTIME ).append( sumTtime/3600  );
				table.doubleColumn( HeadersKN.MONEY).append( sumMoney + dailyCarMoney );
			table.doubleColumn( HeadersKN.ASCS).append( sumAscs );
			List<String> modes = new ArrayList<>();
			for( TripStructureUtils.Trip trip : TripStructureUtils.getTrips( person.getSelectedPlan() ) ){
				mainModeIdentifier.identifyMainMode( trip.getTripElements() );
				modes.add( mainModeIdentifier.identifyMainMode( trip.getTripElements() ) );
			}
			final String modeString = String.join( "--", modes );
				table.stringColumn( HeadersKN.MODE_SEQ).append( modeString ) ;
				if ( isTestPerson( person.getId() ) ) {
					log.warn("modes={}", modeString );
				}
			List<String> acts = new ArrayList<>();
				double lastActEndTime = 0.;
			for( Activity activity : TripStructureUtils.getActivities( person.getSelectedPlan(), ExcludeStageActivities ) ){
					acts.add( activity.getType().substring( 0, Math.min( 4, activity.getType().length()) ) );
					if ( isTestPerson( person.getId() ) ) {
						// (note that the first act typically has no start time and the last no end time.)
						if ( activity.getStartTime().isUndefined() ) {
							lastActEndTime = activity.getEndTime().seconds();
							log.warn("===");
							log.warn( "personId={}; activity type={}; actStartTime={}; actEndTime={}", person.getId(), activity.getType(), activity.getStartTime(), activity.getEndTime().seconds() );
						} else if ( activity.getEndTime().isUndefined() ) {
							log.warn( "personId={}; activity type={}; duration={}", person.getId(), activity.getType(), (lastActEndTime - activity.getStartTime().seconds()) + 24*3600 );
						} else {
							log.warn( "personId={}; activity type={}; " +
//											  "actStartTime={}; actEndTime={};" +
											  " duration={}", person.getId(), activity.getType()
//									, activity.getStartTime().seconds()/3600., activity.getEndTime().seconds()/3600
									, (activity.getEndTime().seconds() - activity.getStartTime().seconds()));
						}
					}
			}
			table.stringColumn( HeadersKN.ACT_SEQ).append( String.join( "|", acts ) );
		}

		table.addColumns(
				table.doubleColumn( HeadersKN.SCORE )
					 .divide( table.doubleColumn( HeadersKN.UTL_OF_MONEY ) )
					 .setName( HeadersKN.BENEFIT )
						) ;

		return table;
	}
	private void readPolicyDataAndCompare( Path inputPath, String pattern, Table tableBase ) throws IOException {
		log.info("Running on {}", inputPath);

		String baseConfigFilename = globFile( inputPath, "*output_config.xml" ).toString();
		Config config = ConfigUtils.loadConfig(baseConfigFilename);

		String populationFileName = globFile(inputPath, "*output_experienced_plans.xml.gz").toString();

		Population policyPopulation = PopulationUtils.readPopulation( populationFileName );
		log.warn( "popSize={}",policyPopulation.getPersons().size());
		cleanPopulation( policyPopulation );
		log.warn( "popSize={}",policyPopulation.getPersons().size());

		handleEventsfile( inputPath, pattern, policyPopulation );

		Table tablePolicy = generateTableFromPopulation( policyPopulation, config );


//		System.out.println( tablePolicy );

		// Compute overlapping columns (excluding join keys)
		Set<String> leftCols = new HashSet<>(tableBase.columnNames());
		Set<String> rightCols = new HashSet<>(tablePolicy.columnNames());

		leftCols.remove( HeadersKN.PERSON_ID);
		rightCols.remove( HeadersKN.PERSON_ID);

		Set<String> duplicates = new HashSet<>(leftCols);
		duplicates.retainAll(rightCols);

		// Rename duplicates in right-hand table
		for (String dup : duplicates) {
			tablePolicy.column( dup ).setName( HeadersKN.keyTwoOf(  dup ) );
		}

//		Table filteredTable = tablePolicy.where( tablePolicy.stringColumn( keyTwoOf(HeadersKN.MODE_SEQ) ).containsString( "drt" ) );
		Table filteredTable = tablePolicy.where( tablePolicy.stringColumn( HeadersKN.keyTwoOf(HeadersKN.MODE_SEQ ) ).containsString( "pt" ) );
//		Table filteredTable = tablePolicy;

		Table joinedTable = tableBase.joinOn( HeadersKN.PERSON_ID).inner( filteredTable );

		// I can set the format to columns that already exist at this stage:
		joinedTable.numberColumn(HeadersKN.SCORE ).setPrintFormatter(format, "n/a" );
		joinedTable.numberColumn(HeadersKN.TTIME ).setPrintFormatter(format, "n/a" );
		joinedTable.numberColumn(HeadersKN.UTL_OF_MONEY ).setPrintFormatter(format, "n/a" );

		Table deltaTable = Table.create( joinedTable.column( HeadersKN.PERSON_ID)
				,joinedTable.column( HeadersKN.UTL_OF_MONEY)
				, joinedTable.column( HeadersKN.SCORE )
				, deltaColumn( joinedTable, HeadersKN.SCORE)
//				, deltaColumn( joinedTable, HeadersKN.benefit )
				, joinedTable.column( HeadersKN.TTIME )
				, deltaColumn( joinedTable, HeadersKN.TTIME)
//				, joinedTable.column( HeadersKN.money )
				, deltaColumn( joinedTable, HeadersKN.MONEY)
//				, joinedTable.column( HeadersKN.ascs )
				, deltaColumn( joinedTable, HeadersKN.ASCS)
				, joinedTable.column( HeadersKN.MODE_SEQ)
				, joinedTable.column( HeadersKN.keyTwoOf( HeadersKN.MODE_SEQ) )
//				, joinedTable.column( HeadersKN.actSeq )
//				, joinedTable.column( keyTwoOf( HeadersKN.actSeq ) )
				, joinedTable.column( HeadersKN.STUCK )
									   );

		log.warn("d_score_mean={}, d_score_ttime_mean={}, d_money_mean={}, d_asc_mean={}"
				, deltaTable.doubleColumn( HeadersKN.deltaOf( HeadersKN.SCORE ) ).mean()
				, deltaTable.doubleColumn( HeadersKN.deltaOf( HeadersKN.TTIME) ).mean() / 3600 * 6
				, deltaTable.doubleColumn( HeadersKN.deltaOf( HeadersKN.MONEY) ).mean()
				, deltaTable.doubleColumn( HeadersKN.deltaOf( HeadersKN.ASCS ) ).mean()
				);

		Table sortedTable = deltaTable.sortOn( HeadersKN.deltaOf( HeadersKN.SCORE) );

		sortedTable.numberColumn( HeadersKN.deltaOf(HeadersKN.SCORE ) ).setPrintFormatter(format, "n/a" );
		sortedTable.numberColumn( HeadersKN.deltaOf(HeadersKN.MONEY ) ).setPrintFormatter(format, "n/a" );
		sortedTable.numberColumn( HeadersKN.deltaOf(HeadersKN.TTIME ) ).setPrintFormatter(format, "n/a" );
		sortedTable.numberColumn( HeadersKN.deltaOf(HeadersKN.ASCS ) ).setPrintFormatter(format, "n/a" );

		log.info( sortedTable );
		throw new RuntimeException("We do not want to continue here, aborting!");

	}
	private static DoubleColumn deltaColumn( Table joinedTable, String key ){
		return joinedTable.doubleColumn( HeadersKN.keyTwoOf( key ) ).subtract( joinedTable.doubleColumn( key ) ).setName( HeadersKN.deltaOf( key ) ) ;
	}

	private static boolean isTestPerson( Id<Person> personId ){
		return switch( personId.toString() ){
			case "766222", "459926", "1279437", "1055071", "1083364", "1450752", "114301" , "203311",
					// dresden:
					"1084690","34371","843219","588488",
				// berlin:
				 "berlin_c5d528ea"
					-> true;
			default -> false;
		};
	}

	private static void cleanPopulation( Population basePopulation ){
		List<Id<Person>> personsToRemove = new ArrayList<>();
		for( Person person : basePopulation.getPersons().values() ){
			Id<Person> personId = person.getId();
//			if ( personId.toString().contains("goods") || personId.toString().contains("commercial") || personId.toString().contains("freight")  ) {
			if ( !"person".equals( PopulationUtils.getSubpopulation( person ) ) ) {
				personsToRemove.add( person.getId() );
			}
		}
		for( Id<Person> personId : personsToRemove ){
			basePopulation.removePerson( personId );
		}
	}

	private static class MyMoneyEventsHandler implements PersonMoneyEventHandler{
		private final Population population;
		public MyMoneyEventsHandler( Population population ){
			this.population = population;
		}
		@Override public void handleEvent( PersonMoneyEvent event ){
			Person person = population.getPersons().get( event.getPersonId() );
			Double moneyAttrib = (Double) person.getAttributes().getAttribute( KN_MONEY );
			if ( moneyAttrib == null ) {
				person.getAttributes().putAttribute( KN_MONEY, event.getAmount() );
			} else {
				person.getAttributes().putAttribute( KN_MONEY, moneyAttrib + event.getAmount() );
			}
		}
	}

	private static class MyStuckEventsHandler implements PersonStuckEventHandler {
		private final Population population;
		public MyStuckEventsHandler( Population population ){
			this.population = population;
		}
		@Override public void handleEvent( PersonStuckEvent event ){
			Person result = population.removePerson( event.getPersonId() );
		}
	}





}
