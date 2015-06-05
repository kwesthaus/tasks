package org.tasks.location;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.google.common.base.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tasks.R;
import org.tasks.injection.ForApplication;
import org.tasks.preferences.Preferences;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.google.android.gms.location.Geofence.NEVER_EXPIRE;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.Collections.singletonList;

public class GeofenceApi {

    private static final Logger log = LoggerFactory.getLogger(GeofenceApi.class);

    private Context context;
    private Preferences preferences;

    @Inject
    public GeofenceApi(@ForApplication Context context, Preferences preferences) {
        this.context = context;
        this.preferences = preferences;
    }

    public void register(final List<Geofence> geofences) {
        if (geofences.isEmpty() || !preferences.geofencesEnabled()) {
            return;
        }

        newClient(new GoogleApi.GoogleApiClientConnectionHandler() {
            @Override
            public void onConnect(final GoogleApiClient client) {
                PendingResult<Status> result = LocationServices.GeofencingApi.addGeofences(
                        client,
                        getRequests(geofences),
                        PendingIntent.getService(context, 0, new Intent(context, GeofenceTransitionsIntentService.class), PendingIntent.FLAG_UPDATE_CURRENT));
                result.setResultCallback(new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status status) {
                        if (status.isSuccess()) {
                            log.info("Registered {}", geofences);
                        } else {
                            log.error("Failed to register {}", geofences);
                        }

                        client.disconnect();
                    }
                });
            }
        });
    }

    public void cancel(final Geofence geofence) {
        cancel(singletonList(geofence));
    }

    public void cancel(final List<Geofence> geofences) {
        if (geofences.isEmpty()) {
            return;
        }

        final List<String> ids = newArrayList(transform(geofences, new Function<Geofence, String>() {
            @Override
            public String apply(Geofence geofence) {
                return Long.toString(geofence.getMetadataId());
            }
        }));

        newClient(new GoogleApi.GoogleApiClientConnectionHandler() {
            @Override
            public void onConnect(final GoogleApiClient client) {
                LocationServices.GeofencingApi.removeGeofences(client, ids)
                        .setResultCallback(new ResultCallback<Status>() {
                            @Override
                            public void onResult(Status status) {
                                if (status.isSuccess()) {
                                    log.info("Removed {}", geofences);
                                } else {
                                    log.error("Failed to remove {}", geofences);
                                }

                                client.disconnect();
                            }
                        });
            }
        });
    }

    private void newClient(final GoogleApi.GoogleApiClientConnectionHandler handler) {
        new GoogleApi(context).connect(handler);
    }

    private List<com.google.android.gms.location.Geofence> getRequests(List<Geofence> geofences) {
        return newArrayList(transform(geofences, new Function<Geofence, com.google.android.gms.location.Geofence>() {
            @Override
            public com.google.android.gms.location.Geofence apply(Geofence geofence) {
                return toGoogleGeofence(geofence);
            }
        }));
    }

    private com.google.android.gms.location.Geofence toGoogleGeofence(Geofence geofence) {
        int radius = preferences.getIntegerFromString(R.string.p_geofence_radius, 250);
        int responsiveness = (int) TimeUnit.SECONDS.toMillis(preferences.getIntegerFromString(R.string.p_geofence_responsiveness, 60));
        return new com.google.android.gms.location.Geofence.Builder()
                .setCircularRegion(geofence.getLatitude(), geofence.getLongitude(), radius)
                .setNotificationResponsiveness(responsiveness)
                .setRequestId(Long.toString(geofence.getMetadataId()))
                .setTransitionTypes(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .setExpirationDuration(NEVER_EXPIRE)
                .build();
    }
}
