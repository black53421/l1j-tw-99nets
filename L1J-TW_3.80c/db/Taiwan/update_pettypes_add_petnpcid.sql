-- Apply this migration only to an existing database that does not yet have PetNpcId.
ALTER TABLE `pettypes`
    ADD COLUMN `PetNpcId` INT(10) NOT NULL DEFAULT '0' AFTER `BaseNpcId`;
