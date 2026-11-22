package com.jenkinschina.atom.bitbucket;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomService;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.util.List;
import java.util.Locale;

/**
 * Bitbucket build status — one atom for the two Bitbucket products, chosen by {@code flavor}.
 *
 * <p>Bitbucket Server / Data Center and Bitbucket Cloud are two deployments with two APIs: Server answers
 * {@code POST {root}/rest/build-status/1.0/commits/{sha}} with five fields and a repository-scoped HTTP token or
 * Basic auth, Cloud answers {@code POST https://api.bitbucket.org/2.0/repositories/{workspace}/{repo}/commit/{sha}
 * /statuses/build} with three required fields and an OAuth2 consumer. They were two atoms
 * ({@code bitbucket-status}, {@code bitbucket-cloud-status}); a user who knows "Bitbucket" had to know which one
 * their installation is before they could pick. Here they are one atom with a {@code flavor} select, and the two
 * implementations are kept apart on purpose: the ports were faithful, so they are dispatched and not unified.
 *
 * <ul>
 *   <li>{@code server} — {@link ServerStatus}, the {@code stashNotifier} / {@code bitbucket-push-and-pull-request}
 *       port: five-field payload, UNSTABLE is FAILED unless asked otherwise, ABORTED is skipped unless asked
 *       otherwise, and the pull-request actions (approve / withdraw / decline / comment).</li>
 *   <li>{@code cloud} — {@link CloudStatus}, the {@code bitbucket-build-status-notifier} 1.4.2 port: OAuth consumer
 *       (or a pre-issued token), {@code md5} keys, owner/repo inferred from {@code GIT_URL}, unknown states
 *       rejected.</li>
 * </ul>
 *
 * <p>The flavour defaults to {@code server}, so a job written for {@code bitbucket-status} keeps working with the
 * same inputs and no new field. Error codes are <em>per flavour</em> and are not renumbered: {@code 40003} is "state
 * not in the Cloud vocabulary" and nothing else, and the same number in the server flavour keeps the meaning it had.
 * The only code this class adds is {@code 40000}, for a {@code flavor} that is neither value.
 */
@AtomService("bitbucket")
public class BitbucketAtom implements AtomTask {

    static final String SERVER = "server";
    static final String CLOUD = "cloud";

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String flavor = context.getParam("flavor", SERVER).trim().toLowerCase(Locale.ROOT);
        if (flavor.isEmpty()) {
            flavor = SERVER;
        }
        return switch (flavor) {
            case SERVER -> ServerStatus.run(context);
            case CLOUD -> CloudStatus.run(context);
            default -> AtomResult.error(ErrorType.USER, 40000,
                    "flavor must be server (Bitbucket Server / Data Center) or cloud (Bitbucket Cloud), got '"
                            + flavor + "' — the two are different APIs, so this atom cannot guess: " + List.of(SERVER, CLOUD));
        };
    }
}
