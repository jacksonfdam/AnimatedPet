Drop your sprite sheet here as:

    pet_sheet.png

The code (SpriteSheet.load / PetAnimations) expects a 5-column x 4-row grid
of equally sized frames. If your sheet has a different layout, change the
`cols` / `rows` arguments in PetOverlayService (SpriteSheet.load call) and
update the frame index arrays in PetAnimations.kt.

Until you add pet_sheet.png the app still runs and shows a blue placeholder
circle instead of the pet.
