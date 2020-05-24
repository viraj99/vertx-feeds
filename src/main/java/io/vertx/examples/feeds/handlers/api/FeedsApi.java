package io.vertx.examples.feeds.handlers.api;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.examples.feeds.dao.MongoDAO;
import io.vertx.examples.feeds.dao.RedisDAO;
import io.vertx.examples.feeds.utils.StringUtils;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Simple CRUD for handling feeds
 */
public class FeedsApi {

	private static final Logger LOG = LoggerFactory.getLogger(FeedsApi.class);
	public static final String COLUMN_SUBSCRIPTIONS = "subscriptions";
	public static final String COLUMN_FEED_ID = "feedId";

	private final MongoDAO mongo;
	private final RedisDAO redis;
	private final StringUtils strUtils;

	public FeedsApi(MongoDAO mongo, RedisDAO redis) {
		this.mongo = mongo;
		this.redis = redis;
		this.strUtils = new StringUtils();
	}

	public void create(RoutingContext rc) {
		var body = rc.getBodyAsJson();
		var user = extractUser(rc);
		var subscriptions = user.getJsonArray(COLUMN_SUBSCRIPTIONS);
		var urlHash = strUtils.hash256(body.getString("url"));
		body.put("hash", urlHash);
		if (subscriptions != null) {
			boolean alreadySubscribed = subscriptions
                    .stream()
                    .anyMatch(subscription ->
					    ((JsonObject) subscription).getString("hash").equals(urlHash)
			        );
			if (alreadySubscribed) {
				rc.fail(400);
				return;
			}
		}
		mongo.newSubscription(user, body)
                .setHandler(result -> {
                    if (result.failed()) {
                        rc.fail(result.cause());
                        return;
                    }
                    rc.response().end(body.toString());
                });
	}

	public void retrieve(RoutingContext rc) {
		var request = rc.request();
		var feedId = request.getParam(COLUMN_FEED_ID);
		if (feedId == null) {
			rc.fail(400);
			return;
		}
		var user = extractUser(rc);
		var subscription = getSubscription(user, rc);
		if (subscription != null) {
			rc.response().end(subscription.toString());
		}
	}

	public void update(RoutingContext rc) {
		rc.response().end("TODO");
	}

	public void delete(RoutingContext rc) {
		var user = extractUser(rc);
		var subscription = getSubscription(user, rc);
		if (subscription == null) {
			return;
		}
		var feedId = rc.request().getParam(COLUMN_FEED_ID);
		subscription.put("hash", feedId);
		mongo.unsubscribe(user, subscription)
                .setHandler(result -> {
                    if (result.failed()) {
                        rc.fail(result.cause());
                        return;
                    }
                    rc.response().end(subscription.toString());
                });
	}

	public void list(RoutingContext rc) {
		var user = extractUser(rc);
		var subscriptions = user.getJsonArray(COLUMN_SUBSCRIPTIONS);
		if (subscriptions == null) {
			subscriptions = new JsonArray();
		}
		rc.response().end(subscriptions.toString());
	}

	public void entries(RoutingContext rc) {
		var user = extractUser(rc);
		var feed = getSubscription(user, rc);
		if (feed != null) {
			var feedId = rc.request().getParam(COLUMN_FEED_ID);
			redis.allEntriesForFeed(feedId, null, null)
                    .setHandler(res -> {
                        if (res.failed()) {
                            rc.fail(res.cause());
                        } else {
                            rc.response().end(res.result().encode());
                        }
                    });
		}
	}

	private static JsonObject getSubscription(JsonObject user, RoutingContext rc) {
		var feedId = rc.request().getParam(COLUMN_FEED_ID);
		if (feedId == null) {
			rc.fail(400);
			return null;
		}
		var subscriptions = user.getJsonArray(COLUMN_SUBSCRIPTIONS);
		if (subscriptions == null) {
			rc.fail(404);
			return null;
		}
		Optional<Object> optional = subscriptions.stream().filter(sub -> {
			JsonObject subscription = (JsonObject) sub;
			return subscription.getString("hash").equals(feedId);
		}).findFirst();
		if (optional.isPresent()) {
			// OK, it's one of user's subscription
			return (JsonObject) optional.get();
		} else { // either the feed doesn't exist, or the user tries to access
			     // someone else's feed -> no distinction
			rc.fail(403);
			return null;
		}
	}

	private JsonObject extractUser(RoutingContext rc) {
	    return rc.get("user");
    }

}
