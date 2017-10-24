/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.manager;

import alfio.manager.support.CheckInStatus;
import alfio.manager.support.DefaultCheckInResult;
import alfio.manager.support.OnSitePaymentResult;
import alfio.manager.support.TicketAndCheckInResult;
import alfio.manager.system.ConfigurationManager;
import alfio.model.*;
import alfio.model.Ticket.TicketStatus;
import alfio.model.audit.ScanAudit;
import alfio.model.transaction.PaymentProxy;
import alfio.repository.*;
import alfio.repository.audit.ScanAuditRepository;
import alfio.repository.user.OrganizationRepository;
import alfio.repository.user.UserRepository;
import alfio.util.Json;
import alfio.util.MonetaryUtil;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static alfio.manager.support.CheckInStatus.*;
import static alfio.model.system.ConfigurationKeys.*;
import static alfio.util.OptionalWrapper.optionally;

@Component
@Transactional
@Log4j2
@AllArgsConstructor
public class CheckInManager {

    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final TicketReservationRepository ticketReservationRepository;
    private final TicketFieldRepository ticketFieldRepository;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final ScanAuditRepository scanAuditRepository;
    private final AuditingRepository auditingRepository;
    private final ConfigurationManager configurationManager;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final TicketReservationManager ticketReservationManager;


    private void checkIn(String uuid) {
        Ticket ticket = ticketRepository.findByUUID(uuid);
        Validate.isTrue(ticket.getStatus() == TicketStatus.ACQUIRED);
        ticketRepository.updateTicketStatusWithUUID(uuid, TicketStatus.CHECKED_IN.toString());
        ticketRepository.toggleTicketLocking(ticket.getId(), ticket.getCategoryId(), true);
    }

    private void acquire(String uuid) {
        Ticket ticket = ticketRepository.findByUUID(uuid);
        Validate.isTrue(ticket.getStatus() == TicketStatus.TO_BE_PAID);
        ticketRepository.updateTicketStatusWithUUID(uuid, TicketStatus.ACQUIRED.toString());
        ticketReservationManager.registerAlfioTransaction(eventRepository.findById(ticket.getEventId()), ticket.getTicketsReservationId(), PaymentProxy.ON_SITE);
    }

    public TicketAndCheckInResult confirmOnSitePayment(String eventName, String ticketIdentifier, Optional<String> ticketCode, String user) {
        return eventRepository.findOptionalByShortName(eventName)
            .flatMap(e -> confirmOnSitePayment(ticketIdentifier).map((String s) -> Pair.of(s, e)))
            .map(p -> checkIn(p.getRight().getId(), ticketIdentifier, ticketCode, user))
            .orElseGet(() -> new TicketAndCheckInResult(null, new DefaultCheckInResult(CheckInStatus.TICKET_NOT_FOUND, "")));
    }

    public Optional<String> confirmOnSitePayment(String ticketIdentifier) {
        Optional<String> uuid = findAndLockTicket(ticketIdentifier)
            .filter(t -> t.getStatus() == TicketStatus.TO_BE_PAID)
            .map(Ticket::getUuid);

        uuid.ifPresent(this::acquire);
        return uuid;
    }

    public TicketAndCheckInResult checkIn(String shortName, String ticketIdentifier, Optional<String> ticketCode, String user) {
        return eventRepository.findOptionalByShortName(shortName).map(e -> checkIn(e.getId(), ticketIdentifier, ticketCode, user)).orElseGet(() -> new TicketAndCheckInResult(null, new DefaultCheckInResult(CheckInStatus.EVENT_NOT_FOUND, "event not found")));
    }

    public TicketAndCheckInResult checkIn(int eventId, String ticketIdentifier, Optional<String> ticketCode, String user) {
        TicketAndCheckInResult descriptor = extractStatus(eventId, ticketRepository.findByUUIDForUpdate(ticketIdentifier), ticketIdentifier, ticketCode);
        if(descriptor.getResult().getStatus() == OK_READY_TO_BE_CHECKED_IN) {
            checkIn(ticketIdentifier);
            scanAuditRepository.insert(ticketIdentifier, eventId, ZonedDateTime.now(), user, SUCCESS, ScanAudit.Operation.SCAN);
            auditingRepository.insert(descriptor.getTicket().getTicketsReservationId(), userRepository.findIdByUserName(user).orElse(null), eventId, Audit.EventType.CHECK_IN, new Date(), Audit.EntityType.TICKET, Integer.toString(descriptor.getTicket().getId()));
            return new TicketAndCheckInResult(descriptor.getTicket(), new DefaultCheckInResult(SUCCESS, "success"));
        }
        return descriptor;
    }

    public boolean manualCheckIn(int eventId, String ticketIdentifier, String user) {
        Optional<Ticket> ticket = findAndLockTicket(ticketIdentifier);
        return ticket.map((t) -> {

            if(t.getStatus() == TicketStatus.TO_BE_PAID) {
                acquire(ticketIdentifier);
            }

            checkIn(ticketIdentifier);
            scanAuditRepository.insert(ticketIdentifier, eventId, ZonedDateTime.now(), user, SUCCESS, ScanAudit.Operation.SCAN);
            auditingRepository.insert(t.getTicketsReservationId(), userRepository.findIdByUserName(user).orElse(null), eventId, Audit.EventType.MANUAL_CHECK_IN, new Date(), Audit.EntityType.TICKET, Integer.toString(t.getId()));
            return true;
        }).orElse(false);
    }

    public boolean revertCheckIn(int eventId, String ticketIdentifier, String user) {
        return findAndLockTicket(ticketIdentifier).map((t) -> {
            if(t.getStatus() == TicketStatus.CHECKED_IN) {
                TicketReservation reservation = ticketReservationRepository.findReservationById(t.getTicketsReservationId());
                TicketStatus revertedStatus = reservation.getPaymentMethod() == PaymentProxy.ON_SITE ? TicketStatus.TO_BE_PAID : TicketStatus.ACQUIRED;
                ticketRepository.updateTicketStatusWithUUID(ticketIdentifier, revertedStatus.toString());
                scanAuditRepository.insert(ticketIdentifier, eventId, ZonedDateTime.now(), user, OK_READY_TO_BE_CHECKED_IN, ScanAudit.Operation.REVERT);
                auditingRepository.insert(t.getTicketsReservationId(), userRepository.findIdByUserName(user).orElse(null), eventId, Audit.EventType.REVERT_CHECK_IN, new Date(), Audit.EntityType.TICKET, Integer.toString(t.getId()));
                return true;
            }
            return false;
        }).orElse(false);
    }

    private Optional<Ticket> findAndLockTicket(String uuid) {
        return ticketRepository.findByUUIDForUpdate(uuid);
    }

    public List<FullTicketInfo> findAllFullTicketInfo(int eventId) {
        return ticketRepository.findAllFullTicketInfoAssignedByEventId(eventId);
    }

    public TicketAndCheckInResult evaluateTicketStatus(int eventId, String ticketIdentifier, Optional<String> ticketCode) {
        return extractStatus(eventRepository.findOptionalById(eventId), ticketRepository.findOptionalByUUID(ticketIdentifier), ticketIdentifier, ticketCode);
    }

    public TicketAndCheckInResult evaluateTicketStatus(String eventName, String ticketIdentifier, Optional<String> ticketCode) {
        return extractStatus(eventRepository.findOptionalByShortName(eventName), ticketRepository.findOptionalByUUID(ticketIdentifier), ticketIdentifier, ticketCode);
    }

    private TicketAndCheckInResult extractStatus(int eventId, Optional<Ticket> maybeTicket, String ticketIdentifier, Optional<String> ticketCode) {
        return extractStatus(eventRepository.findOptionalById(eventId), maybeTicket, ticketIdentifier, ticketCode);
    }

    private TicketAndCheckInResult extractStatus(Optional<Event> maybeEvent, Optional<Ticket> maybeTicket, String ticketIdentifier, Optional<String> ticketCode) {

        if (!maybeEvent.isPresent()) {
            return new TicketAndCheckInResult(null, new DefaultCheckInResult(EVENT_NOT_FOUND, "Event not found"));
        }

        if (!maybeTicket.isPresent()) {
            return new TicketAndCheckInResult(null, new DefaultCheckInResult(TICKET_NOT_FOUND, "Ticket with uuid " + ticketIdentifier + " not found"));
        }

        if(!ticketCode.filter(StringUtils::isNotEmpty).isPresent()) {
            return new TicketAndCheckInResult(null, new DefaultCheckInResult(EMPTY_TICKET_CODE, "Missing ticket code"));
        }

        Ticket ticket = maybeTicket.get();
        Event event = maybeEvent.get();
        String code = ticketCode.get();

        TicketCategory tc = ticketCategoryRepository.getById(ticket.getCategoryId());

        ZonedDateTime now = ZonedDateTime.now(event.getZoneId());
        if(!tc.hasValidCheckIn(now, event.getZoneId())) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy - hh:mm");
            String from = tc.getValidCheckInFrom() == null ? ".." : formatter.format(tc.getValidCheckInFrom(event.getZoneId()));
            String to = tc.getValidCheckInTo() == null ? ".." : formatter.format(tc.getValidCheckInTo(event.getZoneId()));
            String formattedNow = formatter.format(now);
            return new TicketAndCheckInResult(ticket, new DefaultCheckInResult(INVALID_TICKET_CATEGORY_CHECK_IN_DATE,
                String.format("Invalid check-in date: valid range for category %s is from %s to %s, current time is: %s",
                    tc.getName(), from, to, formattedNow)));
        }

        log.trace("scanned code is {}", code);
        log.trace("true code    is {}", ticket.ticketCode(event.getPrivateKey()));

        if (!code.equals(ticket.ticketCode(event.getPrivateKey()))) {
            return new TicketAndCheckInResult(null, new DefaultCheckInResult(INVALID_TICKET_CODE, "Ticket qr code does not match"));
        }

        final TicketStatus ticketStatus = ticket.getStatus();

        if (ticketStatus == TicketStatus.TO_BE_PAID) {
            return new TicketAndCheckInResult(ticket, new OnSitePaymentResult(MUST_PAY, "Must pay for ticket", MonetaryUtil.centsToUnit(ticket.getFinalPriceCts()), event.getCurrency()));
        }

        if (ticketStatus == TicketStatus.CHECKED_IN) {
            return new TicketAndCheckInResult(ticket, new DefaultCheckInResult(ALREADY_CHECK_IN, "Error: already checked in"));
        }

        if (ticket.getStatus() != TicketStatus.ACQUIRED) {
            return new TicketAndCheckInResult(ticket, new DefaultCheckInResult(INVALID_TICKET_STATE, "Invalid ticket state, expected ACQUIRED state, received " + ticket.getStatus()));
        }

        return new TicketAndCheckInResult(ticket, new DefaultCheckInResult(OK_READY_TO_BE_CHECKED_IN, "Ready to be checked in"));
    }

    private static Pair<Cipher, SecretKeySpec>  getCypher(String key) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            int iterations = 1000;
            int keyLength = 256;
            PBEKeySpec spec = new PBEKeySpec(key.toCharArray(), key.getBytes(StandardCharsets.UTF_8), iterations, keyLength);
            SecretKey secretKey = factory.generateSecret(spec);
            SecretKeySpec secret = new SecretKeySpec(secretKey.getEncoded(), "AES");
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            return Pair.of(cipher, secret);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String encrypt(String key, String payload)  {
        try {
            Pair<Cipher, SecretKeySpec> cipherAndSecret = getCypher(key);
            Cipher cipher = cipherAndSecret.getKey();
            cipher.init(Cipher.ENCRYPT_MODE, cipherAndSecret.getRight());
            byte[] data = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            return Base64.encodeBase64URLSafeString(iv) + "|" + Base64.encodeBase64URLSafeString(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String decrypt(String key, String payload) {
        try {
            Pair<Cipher, SecretKeySpec> cipherAndSecret = getCypher(key);
            Cipher cipher = cipherAndSecret.getKey();
            String[] splitted = payload.split(Pattern.quote("|"));
            byte[] iv = Base64.decodeBase64(splitted[0]);
            byte[] body = Base64.decodeBase64(splitted[1]);
            cipher.init(Cipher.DECRYPT_MODE, cipherAndSecret.getRight(), new IvParameterSpec(iv));
            byte[] decrypted = cipher.doFinal(body);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Integer> getAttendeesIdentifiers(Event ev, Date changedSince, String username) {
        return Optional.ofNullable(ev)
            .filter(EventManager.checkOwnership(username, organizationRepository))
            .filter(isOfflineCheckInEnabled())
            .map(event -> ticketRepository.findAllAssignedByEventId(event.getId(), changedSince))
            .orElseGet(Collections::emptyList);
    }

    public List<Integer> getAttendeesIdentifiers(int eventId, Date changedSince, String username) {
        return optionally(() -> eventRepository.findById(eventId))
            .filter(EventManager.checkOwnership(username, organizationRepository))
            .map(event -> ticketRepository.findAllAssignedByEventId(event.getId(), changedSince))
            .orElse(Collections.emptyList());
    }

    public List<FullTicketInfo> getAttendeesInformation(int eventId, List<Integer> ids, String username) {
        return optionally(() -> eventRepository.findById(eventId))
            .filter(EventManager.checkOwnership(username, organizationRepository))
            .map(event -> ticketRepository.findAllFullTicketInfoAssignedByEventId(event.getId(), ids))
            .orElse(Collections.emptyList());
    }

    public Predicate<Event> isOfflineCheckInEnabled() {
        return configurationManager.areBooleanSettingsEnabledForEvent(ALFIO_PI_INTEGRATION_ENABLED, OFFLINE_CHECKIN_ENABLED);
    }

    public Predicate<Event> isOfflineCheckInAndLabelPrintingEnabled() {
        return isOfflineCheckInEnabled().and(configurationManager.areBooleanSettingsEnabledForEvent(LABEL_PRINTING_ENABLED));
    }

    public Map<String,String> getEncryptedAttendeesInformation(Event ev, Set<String> additionalFields, List<Integer> ids) {


        return Optional.ofNullable(ev).filter(isOfflineCheckInEnabled()).map(event -> {
            Map<Integer, TicketCategory> categories = ticketCategoryRepository.findByEventIdAsMap(event.getId());
            String eventKey = event.getPrivateKey();

            Function<FullTicketInfo, String> hashedHMAC = ticket -> DigestUtils.sha256Hex(ticket.hmacTicketInfo(eventKey));

            Function<FullTicketInfo, String> encryptedBody = ticket -> {
                Map<String, String> info = new HashMap<>();
                info.put("firstName", ticket.getFirstName());
                info.put("lastName", ticket.getLastName());
                info.put("fullName", ticket.getFullName());
                info.put("email", ticket.getEmail());
                info.put("status", ticket.getStatus().toString());
                info.put("uuid", ticket.getUuid());
                info.put("category", ticket.getTicketCategory().getName());
                if (!additionalFields.isEmpty()) {
                    Map<String, String> map = ticketFieldRepository.findValueForTicketId(ticket.getId(), additionalFields).stream().collect(Collectors.toMap(TicketFieldValue::getName, TicketFieldValue::getValue));
                    info.put("additionalInfoJson", Json.toJson(map));
                }

                //
                TicketCategory tc = categories.get(ticket.getCategoryId());
                if (tc.getValidCheckInFrom() != null) {
                    info.put("validCheckInFrom", Long.toString(tc.getValidCheckInFrom(event.getZoneId()).toEpochSecond()));
                }
                if (tc.getValidCheckInTo() != null) {
                    info.put("validCheckInTo", Long.toString(tc.getValidCheckInTo(event.getZoneId()).toEpochSecond()));
                }
                //
                String key = ticket.ticketCode(eventKey);
                return encrypt(key, Json.toJson(info));
            };
            return ticketRepository.findAllFullTicketInfoAssignedByEventId(event.getId(), ids)
                .stream()
                .collect(Collectors.toMap(hashedHMAC, encryptedBody));

        }).orElseGet(Collections::emptyMap);
    }
}
