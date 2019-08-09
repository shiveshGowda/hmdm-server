/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.plugins.devicelog.rest.resource;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.ApplicationDAO;
import com.hmdm.persistence.DeviceDAO;
import com.hmdm.plugins.devicelog.model.DeviceLogRecord;
import com.hmdm.plugins.devicelog.persistence.DeviceLogDAO;
import com.hmdm.plugins.devicelog.rest.json.AppliedDeviceLogRule;
import com.hmdm.plugins.devicelog.rest.json.DeviceLogFilter;
import com.hmdm.plugins.devicelog.rest.json.UploadedDeviceLogRecord;
import com.hmdm.plugins.devicelog.task.InsertDeviceLogRecordsTask;
import com.hmdm.rest.json.DeviceLookupItem;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.rest.json.PaginatedData;
import com.hmdm.rest.json.Response;
import com.sun.jersey.core.header.ContentDisposition;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.ResponseHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>A resource to be used for accessing the data for <code>Device Log</code> records.</p>
 *
 * @author isv
 */
@Api(tags = {"Plugin - Device Log"})
@Singleton
@Path("/plugins/devicelog/log")
public class DeviceLogResource {

    // A logging service
    private static final Logger logger  = LoggerFactory.getLogger(DeviceLogResource.class);

    // An executor for the log recrods upload tasks
    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    /**
     * <p>An interface to device log records persistence layer.</p>
     */
    private DeviceLogDAO deviceLogDAO;

    private DeviceDAO deviceDAO;

    private ApplicationDAO applicationDAO;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public DeviceLogResource() {
    }

    /**
     * <p>Constructs new <code>DeviceLogResource</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public DeviceLogResource(DeviceLogDAO deviceLogDAO,
                             DeviceDAO deviceDAO,
                             ApplicationDAO applicationDAO) {
        this.deviceLogDAO = deviceLogDAO;
        this.deviceDAO = deviceDAO;
        this.applicationDAO = applicationDAO;
    }

    /**
     * <p>Gets the list of device log records matching the specified filter.</p>
     *
     * @param filter a filter to be used for filtering the records.
     * @return a response with list of device log records matching the specified filter.
     */
    @ApiOperation(
            value = "Search logs",
            notes = "Gets the list of log records matching the specified filter",
            response = PaginatedData.class,
            authorizations = {@Authorization("Bearer Token")}
    )
    @POST
    @Path("/private/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getLogs(DeviceLogFilter filter) {
        try {
            List<DeviceLogRecord> records = this.deviceLogDAO.findAll(filter);
            long count = this.deviceLogDAO.countAll(filter);

            return Response.OK(new PaginatedData<>(records, count));
        } catch (Exception e) {
            logger.error("Failed to search the log records due to unexpected error. Filter: {}", filter, e);
            return Response.INTERNAL_ERROR();
        }
    }

    @ApiOperation(
            value = "Exports logs",
            notes = "Export the list of log records matching the specified filter",
            responseHeaders = {@ResponseHeader(name = "Content-Type")},
            authorizations = {@Authorization("Bearer Token")}
    )
    @POST
    @Path("/private/search/export")
    @Produces(MediaType.APPLICATION_JSON)
    public javax.ws.rs.core.Response exportLogs(DeviceLogFilter filter) {
        filter.setPageNum(1);
        filter.setExport(true);

        ContentDisposition contentDisposition = ContentDisposition.type("attachment").fileName("logs.csv").creationDate(new Date()).build();

        DateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");

        AtomicBoolean stop = new AtomicBoolean(false);


        return javax.ws.rs.core.Response.ok( (StreamingOutput) output -> {
            try {
                List<DeviceLogRecord> records = this.deviceLogDAO.findAll(filter);
                while (!stop.get() && !records.isEmpty()) {
                    records.forEach(log -> {
                        StringBuilder b = new StringBuilder();
                        b.append(log.getDeviceNumber());
                        b.append(",");
                        b.append(dateFormat.format(new Date(log.getCreateTime())));
                        b.append(",");
                        b.append(log.getApplicationPkg());
                        b.append(",");
                        b.append(log.getSeverity());
                        b.append(",");
                        b.append(log.getMessage());
                        b.append('\n');

                        try {
                            output.write(b.toString().getBytes());
                        } catch (IOException e) {
                            logger.error("Failed to write log record {} to output stream. Stopping to export the " +
                                    "further log records.", log, e);
                            stop.set(true);
                        }
                    });

                    output.flush();

                    if (!stop.get()) {
                        filter.setPageNum(filter.getPageNum() + 1);
                        records = this.deviceLogDAO.findAll(filter);
                    }
                }

                output.flush();
            } catch ( Exception e ) {
                logger.error("Failed to export the device log records due to unexpected error. Filter: {}", filter, e);
            }
        } )
                .header("Cache-Control", "no-cache")
                .header( "Content-Type", "text/plain" )
                .header( "Content-Disposition", contentDisposition )
                .build();
    }

    @ApiOperation(
            value = "Upload logs",
            notes = "Uploads the list of log records from device to server",
            response = Response.class
    )
    @POST
    @Path("/list/{deviceNumber}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response uploadLogs(@PathParam("deviceNumber") String deviceNumber,
                               List<UploadedDeviceLogRecord> logs,
                               @Context HttpServletRequest httpRequest) {
        logger.debug("#uploadLogs: {} => {}", deviceNumber, logs);
        try {
            this.executor.submit(
                    new InsertDeviceLogRecordsTask(deviceNumber, httpRequest.getRemoteAddr(), logs, this.deviceLogDAO)
            );
            return Response.OK();
        } catch (Exception e) {
            logger.error("Unexpected error when handling uploaded log records", e);
            return Response.INTERNAL_ERROR();
        }
    }

    @ApiOperation(
            value = "Get log rules",
            notes = "Gets the list of log rules for device",
            response = AppliedDeviceLogRule.class,
            responseContainer = "List"
    )
    @GET
    @Path("/rules/{deviceNumber}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDeviceLogRules(@PathParam("deviceNumber") String deviceNumber) {
        try {
            final List<AppliedDeviceLogRule> deviceLogRules = this.deviceLogDAO.getDeviceLogRules(deviceNumber);
            logger.debug("#getDeviceLogRules: {} => {}", deviceNumber, deviceLogRules);
            return Response.OK(deviceLogRules);
        } catch (Exception e) {
            logger.error("Unexpected error when handling request for device log rules", e);
            return Response.INTERNAL_ERROR();
        }
    }

    /**
     * <p>Gets the list of devices matching the specified filter.</p>
     *
     * @param filter a filter to be used for filtering the records.
     * @return a response with list of devices matching the specified filter.
     */
    @ApiOperation(value = "", hidden = true)
    @POST
    @Path("/private/device/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDevices(DeviceLogFilter filter) {
        try {
            List<DeviceLookupItem> devices = this.deviceDAO.findDevices(filter.getDeviceFilter(), filter.getPageSize());
            return Response.OK(devices);
        } catch (Exception e) {
            logger.error("Failed to search the devices due to unexpected error. Filter: {}", filter, e);
            return Response.INTERNAL_ERROR();
        }
    }

    /**
     * <p>Gets the list of devices matching the specified filter.</p>
     *
     * @param filter a filter to be used for filtering the records.
     * @return a response with list of devices matching the specified filter.
     */
    @ApiOperation(value = "", hidden = true)
    @POST
    @Path("/private/application/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getApplications(DeviceLogFilter filter) {
        try {
            List<LookupItem> applications
                    = this.applicationDAO.getApplicationPkgLookup(filter.getApplicationFilter(), filter.getPageSize());
            return Response.OK(applications);
        } catch (Exception e) {
            logger.error("Failed to search the applications due to unexpected error. Filter: {}", filter, e);
            return Response.INTERNAL_ERROR();
        }
    }
}