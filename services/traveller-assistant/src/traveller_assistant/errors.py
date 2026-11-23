"""The two kinds of failure a tool reports to the model (ADR-0092). Both messages are written for a model to read and
act on, and never hold an address, a credential or anything a traveller typed beyond what is needed."""


class ToolInputError(ValueError):
    """A call the tool refuses; the message says how to correct it."""


class ServiceError(Exception):
    """A SkyHub service could not answer; the message says what to tell the traveller."""
