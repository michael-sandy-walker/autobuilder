# shelfkit

Building blocks for library software, with no framework underneath: a checked ISBN, a catalogue entry, and the
lending policy of a small branch library.

- `org.shelfkit.catalogue.Isbn` -- an ISBN-13, its EAN-13 check digit verified.
- `org.shelfkit.catalogue.CatalogueEntry` -- an ISBN and a title of at most 200 characters.
- `org.shelfkit.lending.LendingPolicy` -- whether a title may go out: not while it is on loan, and not beyond a
  member's limit of three active loans.

Invalid values raise `org.shelfkit.catalogue.CatalogueException`.
