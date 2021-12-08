package com.conveyal.datatools.manager.jobs;

import com.conveyal.datatools.DatatoolsTest;
import com.conveyal.datatools.UnitTest;
import com.conveyal.datatools.manager.auth.Auth0UserProfile;
import com.conveyal.datatools.manager.jobs.feedmerge.MergeFeedsType;
import com.conveyal.datatools.manager.jobs.feedmerge.MergeStrategy;
import com.conveyal.datatools.manager.models.FeedSource;
import com.conveyal.datatools.manager.models.FeedVersion;
import com.conveyal.datatools.manager.models.Project;
import com.conveyal.datatools.manager.persistence.Persistence;
import com.conveyal.gtfs.error.NewGTFSErrorType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static com.conveyal.datatools.TestUtils.assertThatFeedHasNoErrorsOfType;
import static com.conveyal.datatools.TestUtils.assertThatSqlCountQueryYieldsExpectedCount;
import static com.conveyal.datatools.TestUtils.createFeedVersion;
import static com.conveyal.datatools.TestUtils.createFeedVersionFromGtfsZip;
import static com.conveyal.datatools.TestUtils.zipFolderFiles;
import static com.conveyal.datatools.manager.models.FeedRetrievalMethod.MANUALLY_UPLOADED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the various {@link MergeFeedsJob} merge types.
 */
public class MergeFeedsJobTest extends UnitTest {
    private static final Logger LOG = LoggerFactory.getLogger(MergeFeedsJobTest.class);
    private static final Auth0UserProfile user = Auth0UserProfile.createTestAdminUser();
    private static FeedVersion bartVersion1;
    private static FeedVersion bartVersion2SameTrips;
    private static FeedVersion bartVersionOldLite;
    private static FeedVersion bartVersionNewLite;
    private static FeedVersion calTrainVersionLite;
    private static Project project;
    private static FeedVersion napaVersionLite;
    private static FeedVersion bothCalendarFilesVersion;
    private static FeedVersion bothCalendarFilesVersion2;
    private static FeedVersion bothCalendarFilesVersion3;
    private static FeedVersion onlyCalendarVersion;
    private static FeedVersion onlyCalendarDatesVersion;
    /** The base feed for testing the MTC merge strategies. */
    private static FeedVersion fakeTransitBase;
    /** The base feed but with calendar start/end dates that have been transposed to the future. */
    private static FeedVersion fakeTransitFuture;
    /** The base feed with start/end dates that have been transposed to the future AND unique trip and service IDs. */
    private static FeedVersion fakeTransitFutureUnique;
    /** The base feed but with differing service_ids. */
    private static FeedVersion fakeTransitModService;
    /**
     * The base feed (transposed to the future dates), with some trip_ids from the base feed with different signatures
     * and some added trips.
     */
    private static FeedVersion fakeTransitNewSignatureTrips;
    /**
     * The base feed (transposed to the future dates), with some trip_ids from the base feed with the same signature,
     * and some added trips, and a trip from the base feed removed.
     */
    private static FeedVersion fakeTransitSameSignatureTrips;
    /**
     * The base feed (transposed to the future dates), with some trip_ids from the base feed with the same signature,
     * and a trip from the base feed removed.
     */
    private static FeedVersion fakeTransitSameSignatureTrips2;
    private static FeedSource bart;
    private static FeedVersion noAgencyVersion1;
    private static FeedVersion noAgencyVersion2;

    /**
     * Prepare and start a testing-specific web server
     */
    @BeforeAll
    public static void setUp() throws IOException {
        // start server if it isn't already running
        DatatoolsTest.setUp();

        // Create a project, feed sources, and feed versions to merge.
        project = new Project();
        project.name = String.format("Test %s", new Date());
        Persistence.projects.create(project);

        // Bart
        bart = new FeedSource("BART", project.id, MANUALLY_UPLOADED);
        Persistence.feedSources.create(bart);
        bartVersion1 = createFeedVersionFromGtfsZip(bart, "bart_old.zip");
        bartVersion2SameTrips = createFeedVersionFromGtfsZip(bart, "bart_new.zip");
        bartVersionOldLite = createFeedVersionFromGtfsZip(bart, "bart_old_lite.zip");
        bartVersionNewLite = createFeedVersionFromGtfsZip(bart, "bart_new_lite.zip");

        // Caltrain
        FeedSource caltrain = new FeedSource("Caltrain", project.id, MANUALLY_UPLOADED);
        Persistence.feedSources.create(caltrain);
        calTrainVersionLite = createFeedVersionFromGtfsZip(caltrain, "caltrain_gtfs_lite.zip");

        // Napa
        FeedSource napa = new FeedSource("Napa", project.id, MANUALLY_UPLOADED);
        Persistence.feedSources.create(napa);
        napaVersionLite = createFeedVersionFromGtfsZip(napa, "napa-no-agency-id-lite.zip");

        // Fake agencies (for testing calendar service_id merges with MTC strategy).
        FeedSource fakeAgency = new FeedSource("Fake Agency", project.id, MANUALLY_UPLOADED);
        Persistence.feedSources.create(fakeAgency);
        bothCalendarFilesVersion = createFeedVersion(
            fakeAgency,
            zipFolderFiles("fake-agency-with-calendar-and-calendar-dates")
        );
        onlyCalendarVersion = createFeedVersion(
            fakeAgency,
            zipFolderFiles("fake-agency-with-only-calendar")
        );
        onlyCalendarDatesVersion = createFeedVersion(
            fakeAgency,
            zipFolderFiles("fake-agency-with-only-calendar-dates")
        );
        bothCalendarFilesVersion2 = createFeedVersion(
            fakeAgency,
            zipFolderFiles("fake-agency-with-calendar-and-calendar-dates-2")
        );
        bothCalendarFilesVersion3 = createFeedVersion(
            fakeAgency,
            zipFolderFiles("fake-agency-with-calendar-and-calendar-dates-3")
        );

        // Other fake feeds for testing MTC MergeStrategy types.
        FeedSource fakeTransit = new FeedSource("Fake Transit", project.id, MANUALLY_UPLOADED);
        Persistence.feedSources.create(fakeTransit);
        fakeTransitBase = createFeedVersion(fakeTransit, zipFolderFiles("merge-data-base"));
        fakeTransitFuture = createFeedVersion(fakeTransit, zipFolderFiles("merge-data-future"));
        fakeTransitFutureUnique = createFeedVersion(fakeTransit, zipFolderFiles("merge-data-future-unique-ids"));
        fakeTransitModService = createFeedVersion(fakeTransit, zipFolderFiles("merge-data-mod-services"));
        fakeTransitNewSignatureTrips = createFeedVersion(fakeTransit, zipFolderFiles("merge-data-mod-trips"));
        fakeTransitSameSignatureTrips = createFeedVersion(fakeTransit, zipFolderFiles("merge-data-added-trips"));
        fakeTransitSameSignatureTrips2 = createFeedVersion(fakeTransit, zipFolderFiles("merge-data-added-trips-2"));

        // Feeds with no agency id
        FeedSource noAgencyIds = new FeedSource("no-agency-ids", project.id, MANUALLY_UPLOADED);
        Persistence.feedSources.create(noAgencyIds);
        noAgencyVersion1 = createFeedVersion(noAgencyIds, zipFolderFiles("no-agency-id-1"));
        noAgencyVersion2 = createFeedVersion(noAgencyIds, zipFolderFiles("no-agency-id-2"));
    }

    /**
     * Delete project on tear down (feed sources/versions will also be deleted).
     */
    @AfterAll
    public static void tearDown() {
        if (project != null) {
            project.delete();
        }
    }

    /**
     * Ensures that a regional feed merge will produce a feed that includes all entities from each feed.
     */
    @Test
    void canMergeRegional() throws SQLException {
        // Set up list of feed versions to merge.
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(bartVersionOldLite);
        versions.add(calTrainVersionLite);
        versions.add(napaVersionLite);
        FeedVersion mergedVersion = regionallyMergeVersions(versions);

        // Ensure the feed has the row counts we expect.
        assertEquals(
            bartVersionOldLite.feedLoadResult.trips.rowCount + calTrainVersionLite.feedLoadResult.trips.rowCount + napaVersionLite.feedLoadResult.trips.rowCount,
            mergedVersion.feedLoadResult.trips.rowCount,
            "trips count for merged feed should equal sum of trips for versions merged."
        );
        assertEquals(
            bartVersionOldLite.feedLoadResult.routes.rowCount + calTrainVersionLite.feedLoadResult.routes.rowCount + napaVersionLite.feedLoadResult.routes.rowCount,
            mergedVersion.feedLoadResult.routes.rowCount,
            "routes count for merged feed should equal sum of routes for versions merged."
            );
        assertEquals(
            mergedVersion.feedLoadResult.stops.rowCount,
            bartVersionOldLite.feedLoadResult.stops.rowCount + calTrainVersionLite.feedLoadResult.stops.rowCount + napaVersionLite.feedLoadResult.stops.rowCount,
            "stops count for merged feed should equal sum of stops for versions merged."
        );
        assertEquals(
            mergedVersion.feedLoadResult.agency.rowCount,
            bartVersionOldLite.feedLoadResult.agency.rowCount + calTrainVersionLite.feedLoadResult.agency.rowCount + napaVersionLite.feedLoadResult.agency.rowCount,
            "agency count for merged feed should equal sum of agency for versions merged."
        );
        assertEquals(
            mergedVersion.feedLoadResult.stopTimes.rowCount,
            bartVersionOldLite.feedLoadResult.stopTimes.rowCount + calTrainVersionLite.feedLoadResult.stopTimes.rowCount + napaVersionLite.feedLoadResult.stopTimes.rowCount,
            "stopTimes count for merged feed should equal sum of stopTimes for versions merged."
        );
        assertEquals(
            mergedVersion.feedLoadResult.calendar.rowCount,
            bartVersionOldLite.feedLoadResult.calendar.rowCount + calTrainVersionLite.feedLoadResult.calendar.rowCount + napaVersionLite.feedLoadResult.calendar.rowCount,
            "calendar count for merged feed should equal sum of calendar for versions merged."
        );
        assertEquals(
            mergedVersion.feedLoadResult.calendarDates.rowCount,
            bartVersionOldLite.feedLoadResult.calendarDates.rowCount + calTrainVersionLite.feedLoadResult.calendarDates.rowCount + napaVersionLite.feedLoadResult.calendarDates.rowCount,
            "calendarDates count for merged feed should equal sum of calendarDates for versions merged."
        );
        // Ensure there are no referential integrity errors, duplicate ID, or wrong number of
        // fields errors.
        assertThatFeedHasNoErrorsOfType(
            mergedVersion.namespace,
            NewGTFSErrorType.REFERENTIAL_INTEGRITY.toString(),
            NewGTFSErrorType.DUPLICATE_ID.toString(),
            NewGTFSErrorType.WRONG_NUMBER_OF_FIELDS.toString()
        );
    }

    /**
     * Tests whether the calendar_dates and trips tables get proper feed scoping in a merge with one feed with only
     * calendar_dates and another with only the calendar.
     */
    @Test
    void canMergeRegionalWithOnlyCalendarFeed () throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(onlyCalendarDatesVersion);
        versions.add(onlyCalendarVersion);
        FeedVersion mergedVersion = regionallyMergeVersions(versions);

        // assert service_ids have been feed scoped properly
        String mergedNamespace = mergedVersion.namespace;

        // - calendar table should have 2 records.
        assertRowCountInTable(mergedNamespace, "calendar", 2);

        // onlyCalendarVersion's common_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='Fake_Agency2:common_id'",
                mergedNamespace
            ),
            1
        );
        // onlyCalendarVersion's only_calendar_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='Fake_Agency2:only_calendar_id'",
                mergedNamespace
            ),
            1
        );

        // - calendar_dates table should have 2 records.
        assertRowCountInTable(mergedNamespace, "calendar_dates", 2);

        // onlyCalendarDatesVersion's common_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar_dates WHERE service_id='Fake_Agency3:common_id'",
                mergedNamespace
            ),
            1
        );
        // onlyCalendarDatesVersion's only_calendar_dates_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar_dates WHERE service_id='Fake_Agency3:only_calendar_dates_id'",
                mergedNamespace
            ),
            1
        );

        // - trips table should have 2 + 1 = 3 records.
        assertRowCountInTable(mergedNamespace, "trips", 3);

        // onlyCalendarDatesVersion's common_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.trips WHERE service_id='Fake_Agency3:common_id'",
                mergedNamespace
            ),
            1
        );
        // 2 trips with onlyCalendarVersion's common_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.trips WHERE service_id='Fake_Agency2:common_id'",
                mergedNamespace
            ),
            2
        );
    }

    /**
     * Ensures that an MTC merge of feeds that has exactly matching trips but mismatched services fails.
     */
    @Test
    void mergeMTCShouldFailOnDuplicateTripsButMismatchedServices() {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(fakeTransitBase);
        versions.add(fakeTransitModService);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        // Result should fail.
        assertFalse(
            mergeFeedsJob.mergeFeedsResult.failed,
            "If feeds have exactly matching trips but mismatched services, new service ids should be created that span both feeds."
        );
    }

    /**
     * Ensures that an MTC merge of feeds with exact matches of service_ids and trip_ids will utilize the
     * {@link MergeStrategy#CHECK_STOP_TIMES} strategy correctly.
     */
    @Test
    void mergeMTCShouldHandleExtendFutureStrategy() throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(fakeTransitBase);
        versions.add(fakeTransitFuture);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        // Result should fail.
        assertFalse(
            mergeFeedsJob.mergeFeedsResult.failed,
            "Merge feeds job should succeed with CHECK_STOP_TIMES strategy."
        );
        assertEquals(
            MergeStrategy.CHECK_STOP_TIMES,
            mergeFeedsJob.mergeFeedsResult.mergeStrategy
        );
        // assert service_ids start_dates have been extended to the start_date of the base feed.
        String mergedNamespace = mergeFeedsJob.mergedVersion.namespace;

        assertNoUnusedServiceIds(mergedNamespace);
        assertNoRefIntegrityErrors(mergedNamespace);

        // - calendar table
        // expect a total of 1 record in calendar table that
        // corresponds to the trip ids present in both active and future feed.
        assertRowCountInTable(mergedNamespace, "calendar", 1);

        // expect that the record in calendar table has the correct start_date.
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format("SELECT count(*) FROM %s.calendar where start_date = '20170918' and monday = 1", mergedNamespace),
            1
        );
    }

    /**
     * Ensures that an MTC merge of feeds with exact matches of service_ids and trip_ids,
     * trip ids having the same signature (same stop times) will utilize the
     * {@link MergeStrategy#CHECK_STOP_TIMES} strategy correctly.
     */
    @Test
    void mergeMTCShouldHandleMatchingTripIdsWithSameSignature() throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(fakeTransitBase);
        versions.add(fakeTransitSameSignatureTrips);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        // Check that correct strategy was used.
        assertEquals(
            MergeStrategy.CHECK_STOP_TIMES,
            mergeFeedsJob.mergeFeedsResult.mergeStrategy
        );
        // Result should succeed.
        assertFalse(
            mergeFeedsJob.mergeFeedsResult.failed,
            "Merge feeds job should succeed with CHECK_STOP_TIMES strategy."
        );

        String mergedNamespace = mergeFeedsJob.mergedVersion.namespace;

        // - calendar table
        // expect a total of 4 records in calendar table:
        // - common_id from the active feed (but start date is changed to one day before first start_date in future feed),
        // - common_id from the future feed (because of one future trip not in the active feed),
        // - common_id cloned and extended for the matching trip id present in both active and future feeds
        //   (from MergeFeedsJob#serviceIdsToCloneAndRename),
        // - only_calendar_id used in the future feed.
        assertRowCountInTable(mergedNamespace, "calendar", 4);

        // Out of all trips from the input datasets, expect 4 trips in merged output.
        // 1 trip from active feed that is not in the future feed,
        // 1 trip in both the active and future feeds, with the same signature (same stop times),
        // 2 trips from the future feed not in the active feed.
        assertRowCountInTable(mergedNamespace, "trips", 4);

        assertNoUnusedServiceIds(mergedNamespace);
        assertNoRefIntegrityErrors(mergedNamespace);

        // expect that 2 calendars (1 common_id extended from future and 1 Fake_Transit1:common_id from active) have
        // start_date pinned to start date of active feed.
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format("SELECT count(*) FROM %s.calendar where start_date = '20170918'", mergedNamespace),
            2
        );
        // One of the calendars above should have been extended
        // until the end date of that entry in the future feed.
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format("SELECT count(*) FROM %s.calendar where start_date = '20170918' and end_date='20170925'", mergedNamespace),
            1
        );
        // The other one should have end_date set to a day before the start of the future feed start date
        // (in the test data, that first date comes from the other calendar entry).
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format("SELECT count(*) FROM %s.calendar where start_date = '20170918' and end_date='20170919'", mergedNamespace),
            1
        );
    }

    /**
     * Ensures that an MTC merge of feeds with exact matches of service_ids and trip_ids,
     * trip ids having the same signature (same stop times) will utilize the
     * {@link MergeStrategy#CHECK_STOP_TIMES} strategy correctly and drop unused future service ids.
     */
    @Test
    void mergeMTCShouldHandleMatchingTripIdsAndDropUnusedFutureCalendar() throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(fakeTransitBase);
        versions.add(fakeTransitSameSignatureTrips2);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        // Check that correct strategy was used.
        assertEquals(
            MergeStrategy.CHECK_STOP_TIMES,
            mergeFeedsJob.mergeFeedsResult.mergeStrategy
        );
        // Result should succeed.
        assertFalse(
            mergeFeedsJob.mergeFeedsResult.failed,
            "Merge feeds job should succeed with CHECK_STOP_TIMES strategy."
        );

        String mergedNamespace = mergeFeedsJob.mergedVersion.namespace;

        // - calendar table
        // expect a total of 3 records in calendar table:
        // - common_id from the active feed (but start date is changed to one day before first start_date in future feed),
        // - common_id cloned and extended for the matching trip id present in both active and future feeds
        //   (from MergeFeedsJob#serviceIdsToCloneAndRename),
        // - only_calendar_id used in the future feed.
        assertRowCountInTable(mergedNamespace, "calendar", 3);

        // Out of all trips from the input datasets, expect 4 trips in merged output.
        // 1 trip from active feed that is not in the future feed,
        // 1 trip in both the active and future feeds, with the same signature (same stop times),
        // 1 trip from the future feed not in the active feed.
        assertRowCountInTable(mergedNamespace, "trips", 3);

        // The calendar_dates entry should be preserved, but remapped to a different id.
        assertRowCountInTable(mergedNamespace, "calendar_dates", 1);

        // The GTFS+ calendar_attributes table should contain the same number of entries as the calendar table.
        assertEquals(
            3,
            mergeFeedsJob.mergeFeedsResult.linesPerTable.get("calendar_attributes").intValue(),
            "Merged calendar_dates table count should equal expected value."
        );

        assertNoUnusedServiceIds(mergedNamespace);
        assertNoRefIntegrityErrors(mergedNamespace);
    }

    /**
     * Ensures that an MTC merge of feeds with trip_ids matching in the active and future feed,
     * but with different signatures (e.g. different stop times) fails.
     */
    @Test
    void mergeMTCShouldHandleMatchingTripIdsWithDifferentSignatures() {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(fakeTransitBase);
        versions.add(fakeTransitNewSignatureTrips);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        // Check that correct strategy was used.
        assertEquals(
            MergeStrategy.CHECK_STOP_TIMES,
            mergeFeedsJob.mergeFeedsResult.mergeStrategy
        );
        // Result should fail.
        assertTrue(
            mergeFeedsJob.mergeFeedsResult.failed,
            "Merge feeds job with trip ids of different signatures should fail."
        );
    }

    /**
     * Ensures that an MTC merge of feeds with disjoint (non-matching) trip_ids will utilize the
     * {@link MergeStrategy#DEFAULT} strategy correctly.
     */
    @Test
    void mergeMTCShouldHandleDisjointTripIds() throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(fakeTransitBase);
        versions.add(fakeTransitFutureUnique);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        // Check that correct strategy was used.
        assertEquals(
            MergeStrategy.DEFAULT,
            mergeFeedsJob.mergeFeedsResult.mergeStrategy
        );
        // Result should succeed.
        assertFalse(
            mergeFeedsJob.mergeFeedsResult.failed,
            "Merge feeds job should utilize DEFAULT strategy."
        );
        // assert service_ids start_dates have been extended to the start_date of the base feed.
        String mergedNamespace = mergeFeedsJob.mergedVersion.namespace;

        // - calendar table
        // expect a total of 3 records in calendar table
        // - 2 records from future feed
        // - 1 records from active feed that is used
        // - the unused record from the active feed is discarded.
        assertRowCountInTable(mergedNamespace, "calendar", 3);

        // The one calendar entry for the active feed should end one day before the first calendar start date
        // of the future feed.
        final String activeCalendarNewEndDate = "20170919"; // One day before 20170920.
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE end_date='%s' AND service_id in ('Fake_Transit1:common_id', 'Fake_Transit1:only_calendar_id')",
                mergedNamespace,
                activeCalendarNewEndDate),
            1
        );

        // - trips table
        // expect a total of 4 records in trips table (all records from original files are included).
        assertRowCountInTable(mergedNamespace, "trips", 4);
    }

    /**
     * Tests that the MTC merge strategy will successfully merge BART feeds.
     */
    @Test
    void canMergeBARTFeeds() throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(bartVersionOldLite);
        versions.add(bartVersionNewLite);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Result should succeed this time.
        mergeFeedsJob.run();
        assertFeedMergeSucceeded(mergeFeedsJob);
        // Check GTFS+ line numbers.
        assertEquals(
            2, // Magic number represents expected number of lines after merge.
            mergeFeedsJob.mergeFeedsResult.linesPerTable.get("directions").intValue(),
            "Merged directions count should equal expected value."
        );
        assertEquals(
            2, // Magic number represents the number of stop_attributes in the merged BART feed.
            mergeFeedsJob.mergeFeedsResult.linesPerTable.get("stop_attributes").intValue(),
            "Merged feed stop_attributes count should equal expected value."
        );
        // Check GTFS file line numbers.
        assertEquals(
            3, // Magic number represents the number of trips in the merged BART feed.
            mergeFeedsJob.mergedVersion.feedLoadResult.trips.rowCount,
            "Merged feed trip count should equal expected value."
        );
        assertEquals(
            1, // Magic number represents the number of routes in the merged BART feed.
            mergeFeedsJob.mergedVersion.feedLoadResult.routes.rowCount,
            "Merged feed route count should equal expected value."
        );
        assertEquals(
            // During merge, if identical shape_id is found in both feeds, active feed shape_id should be feed-scoped.
            bartVersionOldLite.feedLoadResult.shapes.rowCount + bartVersionNewLite.feedLoadResult.shapes.rowCount,
            mergeFeedsJob.mergedVersion.feedLoadResult.shapes.rowCount,
            "Merged feed shapes count should equal expected value."
        );
        // Expect that two calendar dates are excluded from the active feed (because they occur after the first date of
        // the future feed).
        int expectedCalendarDatesCount = bartVersionOldLite.feedLoadResult.calendarDates.rowCount + bartVersionNewLite.feedLoadResult.calendarDates.rowCount - 2;
        assertEquals(
            // During merge, if identical shape_id is found in both feeds, active feed shape_id should be feed-scoped.
            expectedCalendarDatesCount,
            mergeFeedsJob.mergedVersion.feedLoadResult.calendarDates.rowCount,
            "Merged feed calendar_dates count should equal expected value."
        );
        // Ensure there are no referential integrity errors or duplicate ID errors.
        assertThatFeedHasNoErrorsOfType(
            mergeFeedsJob.mergedVersion.namespace,
            NewGTFSErrorType.REFERENTIAL_INTEGRITY.toString(),
            NewGTFSErrorType.DUPLICATE_ID.toString()
        );
    }

    /**
     * Tests that BART feeds with trips of same id but different signatures
     * between active and future feeds cannot be merged per MTC revised merge logic.
     */
    @Test
    void shouldNotMergeBARTFeedsSameTrips() {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(bartVersion1);
        versions.add(bartVersion2SameTrips);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Result should succeed this time.
        mergeFeedsJob.run();
        // Result should fail.
        assertTrue(
            mergeFeedsJob.mergeFeedsResult.failed,
            "Merge feeds job with trips of different signatures should fail."
        );
    }

    /**
     * Tests whether a MTC feed merge of two feed versions correctly feed scopes the service_id's of the feed that is
     * chronologically before the other one. This tests two feeds where one of them has both calendar files, and the
     * other has only the calendar file.
     */
    @Test
    void canMergeFeedsWithMTCForServiceIds1 () throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(bothCalendarFilesVersion);
        versions.add(onlyCalendarVersion);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        assertFeedMergeSucceeded(mergeFeedsJob);
        // assert service_ids have been feed scoped properly
        String mergedNamespace = mergeFeedsJob.mergedVersion.namespace;

        // - calendar table should have 4 records.
        assertRowCountInTable(mergedNamespace, "calendar", 4);

        // bothCalendarFilesVersion's common_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='Fake_Agency1:common_id'",
                mergedNamespace
            ),
            1
        );
        // bothCalendarFilesVersion's both_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='Fake_Agency1:both_id'",
                mergedNamespace
            ),
            1
        );
        // onlyCalendarVersion's common id should not be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='common_id'",
                mergedNamespace
            ),
            1
        );
        // onlyCalendarVersion's only_calendar_id service_id should not be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='only_calendar_id'",
                mergedNamespace
            ),
            1
        );

        // - calendar_dates table should have only 2 records.
        assertRowCountInTable(mergedNamespace, "calendar_dates", 2);

        // bothCalendarFilesVersion's common_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar_dates WHERE service_id='Fake_Agency1:common_id'",
                mergedNamespace
            ),
            1
        );
        // bothCalendarFilesVersion's both_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar_dates WHERE service_id='Fake_Agency1:both_id'",
                mergedNamespace
            ),
            1
        );

        // - trips should have 2 + 1 = 3 records.
        assertRowCountInTable(mergedNamespace, "trips", 3);

        // bothCalendarFilesVersion's common_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.trips WHERE service_id='Fake_Agency1:common_id'",
                mergedNamespace
            ),
            1
        );
        // 2 trips with onlyCalendarVersion's common_id service_id should not be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.trips WHERE service_id='common_id'",
                mergedNamespace
            ),
            2
        );
    }

    /**
     * Tests whether a MTC feed merge of two feed versions correctly feed scopes the service_id's of the feed that is
     * chronologically before the other one. This tests two feeds where one of them has only the calendar_dates files,
     * and the other has only the calendar file.
     */
    @Test
    void canMergeFeedsWithMTCForServiceIds2 () throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(onlyCalendarDatesVersion);
        versions.add(onlyCalendarVersion);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        assertFeedMergeSucceeded(mergeFeedsJob);
        // assert service_ids have been feed scoped properly
        String mergedNamespace = mergeFeedsJob.mergedVersion.namespace;

        // - calendar table should have 4 records.
        assertRowCountInTable(mergedNamespace, "calendar", 2);

        // onlyCalendarVersion's common id should not be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='common_id'",
                mergedNamespace
            ),
            1
        );
        // onlyCalendarVersion's only_calendar_id service_id should not be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='only_calendar_id'",
                mergedNamespace
            ),
            1
        );

        // - calendar_dates table should have only 2 records.
        assertRowCountInTable(mergedNamespace, "calendar_dates", 2);

        // onlyCalendarDatesVersion's common_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar_dates WHERE service_id='Fake_Agency3:common_id'",
                mergedNamespace
            ),
            1
        );
        // onlyCalendarDatesVersion's only_calendar_dates_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar_dates WHERE service_id='Fake_Agency3:only_calendar_dates_id'",
                mergedNamespace
            ),
            1
        );

        // - trips table should have 2 + 1 = 3 records.
        assertRowCountInTable(mergedNamespace, "trips", 3);

        // bothCalendarFilesVersion's common_id service_id should be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.trips WHERE service_id='Fake_Agency3:common_id'",
                mergedNamespace
            ),
            1
        );
        // 2 trips with onlyCalendarVersion's common_id service_id should not be scoped
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.trips WHERE service_id='common_id'",
                mergedNamespace
            ),
            2
        );
        // This fails, but if remappedReferences isn't actually needed maybe the current implementation is good-to-go
        // assertThat(mergeFeedsJob.mergeFeedsResult.remappedReferences, equalTo(1));
    }

    /**
     * Tests whether a MTC feed merge of two feed versions correctly removes calendar records that have overlapping
     * service but keeps calendar_dates records that share service_id with the removed calendar and trips that reference
     * that service_id.
     */
    @Test
    void canMergeFeedsWithMTCForServiceIds3 () throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(bothCalendarFilesVersion);
        versions.add(bothCalendarFilesVersion2);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        assertFeedMergeSucceeded(mergeFeedsJob);
        // assert service_ids have been feed scoped properly
        String mergedNamespace = mergeFeedsJob.mergedVersion.namespace;
        // - calendar table should have 3 records.
        assertRowCountInTable(mergedNamespace, "calendar", 3);

        // - calendar_dates should have 3 records.
        assertRowCountInTable(mergedNamespace, "calendar_dates", 3);

        // - trips table should have 3 records.
        assertRowCountInTable(mergedNamespace, "trips", 3);

        // common_id service_id should be scoped for earlier feed version.
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.trips WHERE service_id='Fake_Agency4:common_id'",
                mergedNamespace
            ),
            1
        );
        // cal_to_remove service_id should be scoped for earlier feed version.
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.trips WHERE service_id='Fake_Agency4:cal_to_remove'",
                mergedNamespace
            ),
            1
        );
        // Amended calendar record from earlier feed version should also have a modified end date (one day before the
        // earliest start_date from the future feed).
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='Fake_Agency4:common_id' AND end_date='20170914'",
                mergedNamespace
            ),
            1
        );
        // Modified cal_to_remove should still exist in calendar_dates. It is modified even though it does not exist in
        // the future feed due to the MTC requirement to update all service_ids in the active feed.
        // See https://github.com/ibi-group/datatools-server/issues/244
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar_dates WHERE service_id='Fake_Agency4:cal_to_remove'",
                mergedNamespace
            ),
            1
        );
    }

    /**
     * Tests whether a MTC feed merge of two feed versions correctly removes calendar records that have overlapping
     * service but keeps calendar_dates records that share service_id with the removed calendar and trips that reference
     * that service_id.
     */
    @Test
    void canMergeFeedsWithMTCForServiceIds4 () throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(bothCalendarFilesVersion);
        versions.add(bothCalendarFilesVersion3);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        assertFeedMergeSucceeded(mergeFeedsJob);
        // assert service_ids have been feed scoped properly
        String mergedNamespace = mergeFeedsJob.mergedVersion.namespace;
        // - calendar table should have 3 records.
        assertRowCountInTable(mergedNamespace, "calendar", 3);

        // - calendar_dates table
        // expect 2 records in calendar_dates table (all records from future feed removed)
        assertRowCountInTable(mergedNamespace, "calendar_dates", 3);

        // - trips table should have 3 records.
        assertRowCountInTable(mergedNamespace, "trips", 3);

        // common_id service_id should be scoped for earlier feed version.
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.trips WHERE service_id='Fake_Agency5:common_id'",
                mergedNamespace
            ),
            1
        );
        // Amended calendar record from earlier feed version should also have a modified end date (one day before the
        // earliest start_date from the future feed).
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format(
                "SELECT count(*) FROM %s.calendar WHERE service_id='Fake_Agency5:common_id' AND end_date='20170914'",
                mergedNamespace
            ),
            1
        );
    }

    /**
     * Verify that merging BART feeds that contain "special stops" (i.e., stops with location_type > 0, including
     * entrances, generic nodes, etc.) handles missing stop codes correctly.
     */
    @Test
    void canMergeBARTFeedsWithSpecialStops() throws SQLException, IOException {
        // Mini BART old/new feeds are pared down versions of the zips (bart_new.zip and bart_old.zip). They each have
        // only one trip and its corresponding stop_times. They do contain a full set of routes and stops. The stops are
        // from a recent (as of August 2021) GTFS file that includes a bunch of new stop records that act as entrances).
        FeedVersion miniBartOld = createFeedVersion(bart, zipFolderFiles("mini-bart-old"));
        FeedVersion miniBartNew = createFeedVersion(bart, zipFolderFiles("mini-bart-new"));
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(miniBartOld);
        versions.add(miniBartNew);
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, "merged_output", MergeFeedsType.SERVICE_PERIOD);
        mergeFeedsJob.run();
        // Job should succeed.
        assertFeedMergeSucceeded(mergeFeedsJob);
        // Verify that the stop count is equal to the number of stops found in each of the input stops.txt files.
        assertRowCountInTable(mergeFeedsJob.mergedVersion.namespace, "stops", 182);
    }

    /**
     * Tests whether feeds without agency ids can be merged.
     * The merged feed should have autogenerated agency ids.
     */
    @Test
    void canMergeFeedsWithoutAgencyIds () throws SQLException {
        Set<FeedVersion> versions = new HashSet<>();
        versions.add(noAgencyVersion1);
        versions.add(noAgencyVersion2);
        FeedVersion mergedVersion = regionallyMergeVersions(versions);

        String mergedNamespace = mergedVersion.namespace;
        // - agency should have 2 records.
        assertRowCountInTable(mergedNamespace, "agency", 2);

        // there shouldn't be records with blank agency_id
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format("SELECT count(*) FROM %s.agency where agency_id = '' or agency_id is null", mergedNamespace),
            0
        );
        // - routes should have 2 records
        assertRowCountInTable(mergedNamespace, "routes", 2);

        // there shouldn't be records with blank agency_id
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format("SELECT count(*) FROM %s.routes where agency_id = '' or agency_id is null", mergedNamespace),
            0
        );
        // - trips should have 4 records
        assertRowCountInTable(mergedNamespace, "trips", 4);
    }

    /**
     * Verifies that a completed merge feeds job did not fail.
     */
    private void assertFeedMergeSucceeded(MergeFeedsJob mergeFeedsJob) {
        if (mergeFeedsJob.mergedVersion.namespace == null || mergeFeedsJob.mergeFeedsResult.failed) {
            fail("Merge feeds job failed: " + String.join(", ", mergeFeedsJob.mergeFeedsResult.failureReasons));
        }
    }

    /**
     * Merges a set of FeedVersions and then creates a new FeedSource and FeedVersion of the merged feed.
     */
    private FeedVersion regionallyMergeVersions(Set<FeedVersion> versions) {
        MergeFeedsJob mergeFeedsJob = new MergeFeedsJob(user, versions, project.id, MergeFeedsType.REGIONAL);
        // Run the job in this thread (we're not concerned about concurrency here).
        mergeFeedsJob.run();
        LOG.info("Regional merged file: {}", mergeFeedsJob.mergedVersion.retrieveGtfsFile().getAbsolutePath());
        return mergeFeedsJob.mergedVersion;
    }

    /**
     * Checks there are no unused service ids.
     */
    private void assertNoUnusedServiceIds(String mergedNamespace) throws SQLException {
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format("SELECT count(*) FROM %s.errors where error_type = 'SERVICE_UNUSED'", mergedNamespace),
            0
        );
    }

    /**
     * Checks there are no referential integrity issues.
     */
    private void assertNoRefIntegrityErrors(String mergedNamespace) throws SQLException {
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format("SELECT count(*) FROM %s.errors where error_type = 'REFERENTIAL_INTEGRITY'", mergedNamespace),
            0
        );
    }

    /**
     * Shorthand method for asserting an expected number of rows in a table.
     */
    private void assertRowCountInTable(String namespace, String tableName, int count) throws SQLException {
        assertThatSqlCountQueryYieldsExpectedCount(
            String.format("SELECT count(*) FROM %s.%s", namespace, tableName),
            count
        );
    }
}
