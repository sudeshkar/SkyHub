package com.skyhub.booking.web;

import com.skyhub.booking.api.SavedPassengersApi;
import com.skyhub.booking.api.model.SavedPassengerDto;
import com.skyhub.booking.api.model.SavedPassengerListDto;
import com.skyhub.booking.api.model.SavedPassengerRequestDto;
import com.skyhub.booking.application.SavedPassengerService;
import com.skyhub.booking.application.SavedPassengers;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/**
 * Needs an account token, checked in SecurityConfig before this runs; the account is the token's subject, never something the caller can
 * name (ADR-0078). Names are personal data, so nothing is cached.
 */
@RestController
public class SavedPassengersController implements SavedPassengersApi {

    private final SavedPassengerService passengers;

    public SavedPassengersController(SavedPassengerService passengers) {
        this.passengers = passengers;
    }

    private static long account() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Override
    public ResponseEntity<SavedPassengerListDto> listSavedPassengers() {
        var list = new SavedPassengerListDto(passengers.list(account()).stream().map(SavedPassengersController::toDto).toList(), SavedPassengerService.LIMIT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(list);
    }

    @Override
    public ResponseEntity<SavedPassengerDto> saveMyPassenger(SavedPassengerRequestDto request) {
        SavedPassengerService.Added added = passengers.add(account(), request.getFirstName(), request.getLastName());
        return ResponseEntity.status(added.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(toDto(added.passenger()));
    }

    @Override
    public ResponseEntity<Void> removeSavedPassenger(Long id) {
        passengers.remove(account(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static SavedPassengerDto toDto(SavedPassengers.Saved saved) {
        return new SavedPassengerDto(saved.id(), saved.firstName(), saved.lastName());
    }
}
