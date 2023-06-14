package com.conveyal.datatools.manager.models.transform;

import com.conveyal.datatools.common.status.MonitorableJob;
import com.conveyal.datatools.manager.models.TableTransformResult;
import com.conveyal.datatools.manager.models.TransformType;
import org.supercsv.io.CsvMapReader;
import com.conveyal.gtfs.loader.Table;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileWriter;
import java.io.StringReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.supercsv.io.CsvMapWriter;
import org.supercsv.prefs.CsvPreference;


/**
 * This feed transformation will attempt to preserve any custom fields from an entered csv in the final GTFS output.
 */
public class PreserveCustomFieldsTransformation extends ZipTransformation {
    private static List<String> tablePrimaryKeys = new ArrayList<>();
    /** no-arg constructor for de/serialization */
    public PreserveCustomFieldsTransformation() {}

    public static PreserveCustomFieldsTransformation create(String sourceVersionId, String table) {
        PreserveCustomFieldsTransformation transformation = new PreserveCustomFieldsTransformation();
        transformation.sourceVersionId = sourceVersionId;
        transformation.table = table;
        return transformation;
    }

    @Override
    public void validateParameters(MonitorableJob.Status status) {
        if (csvData == null) {
            status.fail("CSV data must not be null (delete table not yet supported)");
        }
    }

    /**
     * This method creates a hash map of the GTFS table keys to the custom CSV values for efficient lookup of custom values.
     * The hash map key is the key values of the GTFS table (e.g. stop_id for stops) concatenated by an underscore.
     * The hash map value is the CsvMapReader (mapping of column to row value).
     */
    private static HashMap<String, Map<String, String>> createCsvHashMap(CsvMapReader reader, String[] headers) throws IOException {
        HashMap<String, Map<String, String>> lookup = new HashMap<>();
        Map<String, String> nextLine;
        while ((nextLine = reader.read(headers)) != null) {
            List<String> customCsvKeyValues = tablePrimaryKeys.stream().map(nextLine::get).collect(Collectors.toList());
            String hashKey = StringUtils.join(customCsvKeyValues, "_");
            lookup.put(hashKey, nextLine);
        }
        return lookup;
    }

    @Override
    public void transform(FeedTransformZipTarget zipTarget, MonitorableJob.Status status) {
        String tableName = table + ".txt";
        Path targetZipPath = Paths.get(zipTarget.gtfsFile.getAbsolutePath());

        Table specTable = Arrays.stream(Table.tablesInOrder)
            .filter(t -> t.name.equals(table))
            .findFirst()
            .get();
        List<String> specTableFields = specTable.specFields().stream().map(f -> f.name).collect(Collectors.toList());
        tablePrimaryKeys = specTable.getPrimaryKeyNames();

        try (FileSystem targetZipFs = FileSystems.newFileSystem( targetZipPath, (ClassLoader) null )){
            Path targetTxtFilePath = getTablePathInZip(tableName, targetZipFs);

            final File tempFile = File.createTempFile(tableName + "-temp", ".txt");
            File output = File.createTempFile(tableName + "-output-temp", ".txt");

            try (
                InputStream is = Files.newInputStream(targetTxtFilePath);
                CsvMapReader customFileReader = new CsvMapReader(new StringReader(csvData), CsvPreference.STANDARD_PREFERENCE);
                CsvMapReader editorFileReader = new CsvMapReader(new InputStreamReader(is), CsvPreference.STANDARD_PREFERENCE);
                CsvMapWriter writer = new CsvMapWriter(new FileWriter(output), CsvPreference.STANDARD_PREFERENCE);
            ){

                String[] customHeaders = customFileReader.getHeader(true);
                final String[] editorHeaders = editorFileReader.getHeader(true);

                List<String> customFields = Arrays.stream(customHeaders).filter(h -> !specTableFields.contains(h)).collect(Collectors.toList());
                if (customFields.isEmpty()) return;
                String[] fullHeaders = ArrayUtils.addAll(editorHeaders, customFields.toArray(new String[0]));

                HashMap<String, Map<String, String>> customFieldsLookup = createCsvHashMap(customFileReader, customHeaders);
                writer.writeHeader(fullHeaders);

                Map<String, String> row;
                while ((row = editorFileReader.read(editorHeaders)) != null) {
                    List<String> editorCsvPrimaryKeyValues = tablePrimaryKeys.stream()
                            .map(row::get)
                            .collect(Collectors.toList());

                    String hashKey = StringUtils.join(editorCsvPrimaryKeyValues, "_");
                    Map<String, String> customCsvValues = customFieldsLookup.get(hashKey);
                    Map<String, String> finalRow = row;
                    customFields.stream().forEach(customField -> {
                        String value = customCsvValues == null ? null : customCsvValues.get(customField);
                        finalRow.put(customField, value);
                    });
                    writer.write(finalRow, fullHeaders);
                }
                writer.close();

                Files.copy(output.toPath(), targetTxtFilePath, StandardCopyOption.REPLACE_EXISTING);
                tempFile.deleteOnExit();
                output.deleteOnExit();
                zipTarget.feedTransformResult.tableTransformResults.add(new TableTransformResult(tableName, TransformType.TABLE_MODIFIED));
            }
        } catch (NoSuchFileException e) {
            status.fail("Source version does not contain table: " + tableName, e);
        } catch (IOException e) {
            status.fail("An exception occurred when writing output with custom fields", e);
        } catch (Exception e) {
            status.fail("Unknown error encountered while transforming zip file", e);
        }
    }
}
