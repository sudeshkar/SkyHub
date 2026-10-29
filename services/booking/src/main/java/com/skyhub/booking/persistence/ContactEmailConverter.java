package com.skyhub.booking.persistence;

import com.skyhub.booking.domain.ContactEmail;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Applied automatically to every {@link ContactEmail} attribute, so the domain never mentions
 * encryption and the persistence layer cannot forget it. A Spring bean, so the key is injected.
 */
@Component
@Converter(autoApply = true)
public class ContactEmailConverter implements AttributeConverter<ContactEmail, String> {

    private final PiiCipher cipher;

    public ContactEmailConverter(PiiCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(ContactEmail email) {
        return email == null ? null : cipher.encrypt(email.value());
    }

    @Override
    public ContactEmail convertToEntityAttribute(String stored) {
        return stored == null ? null : new ContactEmail(cipher.decrypt(stored));
    }
}
